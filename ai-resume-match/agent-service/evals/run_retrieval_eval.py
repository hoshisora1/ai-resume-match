from __future__ import annotations

import argparse
import json
import os
import subprocess
from dataclasses import replace
from datetime import UTC, datetime
from functools import partial
from pathlib import Path
from typing import Any

from agent_service.config import Settings
from agent_service.embeddings import OpenAICompatibleEmbeddingClient
from agent_service.retrieval import (
    DEFAULT_DENSE_MIN_SIMILARITY,
    HashingRetriever,
    HybridRetriever,
)
from agent_service.retrieval_evaluation import (
    evaluate_retrieval_dataset,
    load_retrieval_dataset,
)


def git_metadata() -> tuple[str, bool]:
    repository = Path(__file__).resolve().parents[2]
    revision = os.getenv("GITHUB_SHA", "").strip()
    if not revision:
        completed = subprocess.run(
            ["git", "-C", str(repository), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        )
        revision = completed.stdout.strip()
    status = subprocess.run(
        ["git", "-C", str(repository), "status", "--porcelain", "--untracked-files=all"],
        check=True,
        capture_output=True,
        text=True,
    )
    return revision, bool(status.stdout.strip())


def compare_with_baseline(
    summary: dict[str, Any],
    baseline: dict[str, Any],
) -> dict[str, Any]:
    if baseline.get("datasetSha256") != summary["datasetSha256"]:
        raise ValueError("baseline dataset hash does not match the current dataset")
    current_top_k = summary["retriever"]["topK"]
    if baseline.get("retriever", {}).get("topK") != current_top_k:
        raise ValueError("baseline Top-K does not match the current evaluation")
    recall_key = f"recallAt{current_top_k}"
    mrr_key = f"mrrAt{current_top_k}"
    baseline_metrics = baseline["metrics"]
    current_metrics = summary["metrics"]
    return {
        "baselineRetriever": baseline["retriever"]["version"],
        "baselineDatasetSha256": baseline["datasetSha256"],
        "recallLift": round(current_metrics[recall_key] - baseline_metrics[recall_key], 6),
        "mrrLift": round(current_metrics[mrr_key] - baseline_metrics[mrr_key], 6),
        "falsePositiveRateIncrease": round(
            current_metrics["falsePositiveRate"] - baseline_metrics["falsePositiveRate"],
            6,
        ),
    }


def render_markdown(summary: dict[str, Any]) -> str:
    metrics = summary["metrics"]
    retriever = summary["retriever"]
    top_k = retriever["topK"]
    misses = [
        result
        for result in summary["results"]
        if result["recall"] is not None and result["recall"] < 1
    ]
    false_positives = [
        result for result in summary["results"] if result["falsePositive"] is True
    ]

    lines = [
        "# Retrieval evaluation",
        "",
        f"- Dataset: `{summary['datasetVersion']}` (`{summary['datasetSha256']}`)",
        f"- Source revision: `{summary['run']['commit']}`",
        f"- Working tree dirty: `{str(summary['run']['workingTreeDirty']).lower()}`",
        f"- Retriever: `{retriever['version']}`; Top-K: `{retriever['topK']}`",
        "",
        "## Metrics",
        "",
        "| Metric | Value |",
        "| --- | ---: |",
        f"| Cases | {metrics['cases']} |",
        f"| Positive cases | {metrics['positiveCases']} |",
        f"| No-evidence cases | {metrics['noEvidenceCases']} |",
        f"| Recall@{top_k} | {metrics[f'recallAt{top_k}']:.4f} |",
        f"| MRR@{top_k} | {metrics[f'mrrAt{top_k}']:.4f} |",
        f"| False-positive rate | {metrics['falsePositiveRate']:.4f} |",
        f"| Local retrieval p95 | {metrics['retrievalP95Ms']:.4f} ms |",
        "",
        "The latency value is a local diagnostic, not a production SLA. This dataset is synthetic and "
        "is intended for retriever regression comparison, not hiring-outcome accuracy claims.",
        "",
        "## Missed or partially recalled cases",
        "",
    ]
    if misses:
        lines.extend(
            [
                "| Case | Tags | Recall | Reciprocal rank |",
                "| --- | --- | ---: | ---: |",
                *[
                    f"| `{item['id']}` | {', '.join(item['tags'])} | "
                    f"{item['recall']:.4f} | {item['reciprocalRank']:.4f} |"
                    for item in misses
                ],
            ]
        )
    else:
        lines.append("None.")

    lines.extend(["", "## False-positive cases", ""])
    if false_positives:
        lines.extend(f"- `{item['id']}`" for item in false_positives)
    else:
        lines.append("None.")
    comparison = summary.get("comparison")
    if comparison is not None:
        lines.extend(
            [
                "",
                "## Baseline comparison",
                "",
                f"- Baseline retriever: `{comparison['baselineRetriever']}`",
                f"- Recall lift: `{comparison['recallLift']:+.4f}`",
                f"- MRR lift: `{comparison['mrrLift']:+.4f}`",
                "- False-positive rate increase: "
                f"`{comparison['falsePositiveRateIncrease']:+.4f}`",
            ]
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run deterministic retrieval evaluation")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path(__file__).with_name("retrieval_dataset_v1.json"),
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument(
        "--retriever",
        choices=["hashing", "hybrid"],
        default="hashing",
    )
    parser.add_argument(
        "--dense-min-similarity",
        type=float,
        default=DEFAULT_DENSE_MIN_SIMILARITY,
    )
    parser.add_argument("--min-recall", type=float, default=0.0)
    parser.add_argument("--min-mrr", type=float, default=0.0)
    parser.add_argument("--max-false-positive-rate", type=float, default=1.0)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--min-recall-lift", type=float, default=0.0)
    parser.add_argument("--max-false-positive-rate-increase", type=float, default=0.0)
    parser.add_argument("--summary-only", action="store_true")
    args = parser.parse_args()

    dataset, digest = load_retrieval_dataset(args.dataset)
    embedding_client: OpenAICompatibleEmbeddingClient | None = None
    if args.retriever == "hybrid":
        settings = replace(
            Settings.from_env(),
            retriever_mode="hybrid",
            dense_min_similarity=args.dense_min_similarity,
        )
        settings.validate_retrieval()
        embedding_client = OpenAICompatibleEmbeddingClient(
            endpoint=settings.embedding_endpoint,
            api_key=settings.embedding_api_key,
            model=settings.embedding_model,
            timeout_seconds=settings.embedding_timeout_seconds,
            batch_size=settings.embedding_batch_size,
        )
        retriever_factory = partial(
            HybridRetriever,
            embedding_client=embedding_client,
            dense_min_similarity=settings.dense_min_similarity,
        )
        retriever_name = "HybridRetriever"
        retriever_parameters = {
            "denseMinimumSimilarity": settings.dense_min_similarity,
            "fusion": "reciprocal-rank-fusion",
            "embeddingModel": settings.embedding_model,
        }
    else:
        retriever_factory = HashingRetriever
        retriever_name = "HashingRetriever"
        retriever_parameters = None
    try:
        summary = evaluate_retrieval_dataset(
            dataset,
            dataset_sha256=digest,
            top_k=args.top_k,
            retriever_factory=retriever_factory,
            retriever_name=retriever_name,
            retriever_parameters=retriever_parameters,
        )
    finally:
        if embedding_client is not None:
            embedding_client.close()
    revision, dirty = git_metadata()
    summary["run"] = {
        "commit": revision,
        "workingTreeDirty": dirty,
        "generatedAt": datetime.now(UTC).isoformat(),
        "runtime": "python",
    }
    if args.baseline:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        summary["comparison"] = compare_with_baseline(summary, baseline)

    rendered = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    console_payload = (
        {
            "datasetVersion": summary["datasetVersion"],
            "datasetSha256": summary["datasetSha256"],
            "retriever": summary["retriever"],
            "metrics": summary["metrics"],
            "run": summary["run"],
            **(
                {"comparison": summary["comparison"]}
                if "comparison" in summary
                else {}
            ),
        }
        if args.summary_only
        else summary
    )
    print(json.dumps(console_payload, ensure_ascii=False, indent=2))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8", newline="\n")
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(render_markdown(summary), encoding="utf-8", newline="\n")

    metrics = summary["metrics"]
    passed = (
        metrics[f"recallAt{args.top_k}"] >= args.min_recall
        and metrics[f"mrrAt{args.top_k}"] >= args.min_mrr
        and metrics["falsePositiveRate"] <= args.max_false_positive_rate
    )
    if args.baseline:
        comparison = summary["comparison"]
        passed = (
            passed
            and comparison["recallLift"] >= args.min_recall_lift
            and comparison["falsePositiveRateIncrease"]
            <= args.max_false_positive_rate_increase
        )
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
