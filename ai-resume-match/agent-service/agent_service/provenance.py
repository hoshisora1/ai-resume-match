from __future__ import annotations

import hashlib
import struct

from agent_service.models import AnalysisRequest


RUN_METADATA_SCHEMA_VERSION = "agent-run-v1"
REQUEST_SCHEMA_VERSION = "agent-analysis-request-v1"
AGENT_RUNTIME_VERSION = "bounded-tool-agent-v1"
INPUT_FINGERPRINT_VERSION = "sha256-task-scoped-length-prefixed-v1"


def analysis_input_fingerprint(request: AnalysisRequest) -> str:
    """Fingerprint normalized analysis input without exposing or globally linking it.

    The task id deliberately scopes the digest: retries for the same normalized task
    are comparable, while identical documents submitted as different tasks do not
    receive a reusable cross-task content identifier. Correlation ids are operational
    metadata and are intentionally excluded.
    """

    digest = hashlib.sha256()
    values = [
        INPUT_FINGERPRINT_VERSION,
        str(request.task_id),
        request.resume_text,
        request.job_title,
        request.job_description,
        str(len(request.skill_tags)),
        *request.skill_tags,
    ]
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(struct.pack(">Q", len(encoded)))
        digest.update(encoded)
    return digest.hexdigest()
