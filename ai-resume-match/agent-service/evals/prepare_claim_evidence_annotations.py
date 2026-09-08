from __future__ import annotations

import argparse
import json
from pathlib import Path

from agent_service.claim_evidence_evaluation import (
    AnnotationSource,
    build_annotation_template,
    build_annotation_package,
)


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Split an explicit claim-evidence source into blinded tasks and a prediction key"
    )
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--tasks", type=Path, required=True)
    parser.add_argument("--prediction-key", type=Path, required=True)
    parser.add_argument("--label-template", type=Path)
    args = parser.parse_args()

    source = AnnotationSource.model_validate_json(
        args.source.read_text(encoding="utf-8")
    )
    task_set, prediction_key = build_annotation_package(source)
    write_json(args.tasks, task_set.model_dump(by_alias=True))
    write_json(args.prediction_key, prediction_key.model_dump(by_alias=True))
    if args.label_template is not None:
        write_json(args.label_template, build_annotation_template(task_set))
    print(
        json.dumps(
            {
                "schemaVersion": task_set.schema_version,
                "taskSetSha256": task_set.task_set_sha256,
                "pairs": len(task_set.pairs),
                "tasks": str(args.tasks),
                "predictionKey": str(args.prediction_key),
                "labelTemplate": (
                    str(args.label_template) if args.label_template is not None else None
                ),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
