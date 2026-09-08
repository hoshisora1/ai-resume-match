from __future__ import annotations

import argparse
import json
from pathlib import Path

from agent_service.claim_evidence_evaluation import (
    AnnotationSubmission,
    AnnotationTaskSet,
    build_adjudication_template,
)


def load(path: Path, model):
    return model.model_validate_json(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Create an intentionally incomplete template for annotator disagreements"
    )
    parser.add_argument("--tasks", type=Path, required=True)
    parser.add_argument("--annotator-a", type=Path, required=True)
    parser.add_argument("--annotator-b", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    template = build_adjudication_template(
        task_set=load(args.tasks, AnnotationTaskSet),
        annotator_a=load(args.annotator_a, AnnotationSubmission),
        annotator_b=load(args.annotator_b, AnnotationSubmission),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(template, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        json.dumps(
            {
                "taskSetSha256": template["taskSetSha256"],
                "disagreements": len(template["resolutions"]),
                "output": str(args.output),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
