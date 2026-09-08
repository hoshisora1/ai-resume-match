from __future__ import annotations

import argparse
import json
from pathlib import Path

from agent_service.claim_evidence_evaluation import (
    AdjudicationSubmission,
    AnnotationSubmission,
    AnnotationTaskSet,
    PredictionKey,
    evaluate_annotations,
    evaluate_quality_gates,
)


def load(path: Path, model):
    return model.model_validate_json(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Evaluate independently annotated claim-evidence pairs after adjudication"
    )
    parser.add_argument("--tasks", type=Path, required=True)
    parser.add_argument("--prediction-key", type=Path, required=True)
    parser.add_argument("--annotator-a", type=Path, required=True)
    parser.add_argument("--annotator-b", type=Path, required=True)
    parser.add_argument("--adjudication", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--min-pairs", type=int, default=100)
    parser.add_argument("--min-accepted-predictions", type=int, default=100)
    parser.add_argument("--min-rejected-predictions", type=int, default=20)
    parser.add_argument("--min-kappa", type=float, default=0.80)
    parser.add_argument("--max-unsupported-positive-rate", type=float, default=0.02)
    parser.add_argument("--max-false-rejection-rate", type=float, default=0.10)
    args = parser.parse_args()
    for name, value in (
        ("--min-pairs", args.min_pairs),
        ("--min-accepted-predictions", args.min_accepted_predictions),
        ("--min-rejected-predictions", args.min_rejected_predictions),
    ):
        if value < 1:
            parser.error(f"{name} must be positive")
    for name, value in (
        ("--min-kappa", args.min_kappa),
        ("--max-unsupported-positive-rate", args.max_unsupported_positive_rate),
        ("--max-false-rejection-rate", args.max_false_rejection_rate),
    ):
        if not 0 <= value <= 1:
            parser.error(f"{name} must be between 0 and 1")

    report = evaluate_annotations(
        task_set=load(args.tasks, AnnotationTaskSet),
        prediction_key=load(args.prediction_key, PredictionKey),
        annotator_a=load(args.annotator_a, AnnotationSubmission),
        annotator_b=load(args.annotator_b, AnnotationSubmission),
        adjudication=(
            load(args.adjudication, AdjudicationSubmission)
            if args.adjudication is not None
            else None
        ),
    )
    report["qualityGates"] = evaluate_quality_gates(
        report,
        min_pairs=args.min_pairs,
        min_accepted_predictions=args.min_accepted_predictions,
        min_rejected_predictions=args.min_rejected_predictions,
        min_kappa=args.min_kappa,
        max_unsupported_positive_rate=args.max_unsupported_positive_rate,
        max_false_rejection_rate=args.max_false_rejection_rate,
    )
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    print(rendered, end="")
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8", newline="\n")

    return 0 if report["qualityGates"]["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
