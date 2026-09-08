from __future__ import annotations

from collections.abc import Iterable
from decimal import Decimal
from typing import Protocol

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram

from agent_service.models import ModelUsage


ANALYSIS_OUTCOMES = {
    "success",
    "cancelled",
    "protocol_error",
    "model_response_error",
    "embedding_provider_error",
    "deadline",
    "provider_rejected",
    "provider_unavailable",
    "unexpected",
}
PROVIDER_OUTCOMES = {
    "success",
    "cancelled",
    "timeout",
    "rejected",
    "unavailable",
    "invalid_response",
    "unexpected",
}
TOOL_NAMES = {
    "get_job_requirements",
    "search_resume_evidence",
    "submit_match_report",
    "unknown",
}
TOOL_OUTCOMES = {"success", "denied", "cancelled", "error"}
RETRIEVER_KINDS = {"hashing", "hybrid", "dense", "other"}
RETRIEVAL_OUTCOMES = {"hit", "empty", "denied", "cancelled", "error"}
PROTOCOL_REASONS = {
    "token_budget_exhausted",
    "token_budget_exceeded",
    "context_budget_exceeded",
    "missing_tool_call",
    "tool_call_limit",
    "tool_denied",
    "invalid_final_report",
    "step_limit",
    "other",
}
REQUIREMENT_STATUSES = {"supported", "partial", "not_found", "other"}
COST_SKIP_REASONS = {"pricing_not_configured", "usage_not_reported", "other"}


def _bounded(value: str, allowed: set[str], fallback: str) -> str:
    return value if value in allowed else fallback


def safe_tool_name(name: str) -> str:
    return _bounded(name, TOOL_NAMES, "unknown")


def retriever_kind(version: str) -> str:
    normalized = version.casefold()
    for kind in ("hybrid", "hashing", "dense"):
        if normalized.startswith(kind):
            return kind
    return "other"


class AgentMetricsRecorder(Protocol):
    def analysis_started(self) -> None: ...

    def analysis_finished(self, outcome: str, duration_seconds: float) -> None: ...

    def authentication_failed(self) -> None: ...

    def provider_call_finished(
        self,
        outcome: str,
        duration_seconds: float,
        usage: ModelUsage | None = None,
        estimated_cost_usd: Decimal | None = None,
        cost_skip_reason: str | None = None,
    ) -> None: ...

    def tool_call_finished(self, name: str, outcome: str, duration_seconds: float) -> None: ...

    def retrieval_finished(self, retriever: str, outcome: str, evidence_count: int = 0) -> None: ...

    def protocol_error(self, reason: str) -> None: ...

    def requirement_results(self, statuses: Iterable[str]) -> None: ...


class NoOpAgentMetrics:
    def analysis_started(self) -> None:
        pass

    def analysis_finished(self, outcome: str, duration_seconds: float) -> None:
        pass

    def authentication_failed(self) -> None:
        pass

    def provider_call_finished(
        self,
        outcome: str,
        duration_seconds: float,
        usage: ModelUsage | None = None,
        estimated_cost_usd: Decimal | None = None,
        cost_skip_reason: str | None = None,
    ) -> None:
        pass

    def tool_call_finished(self, name: str, outcome: str, duration_seconds: float) -> None:
        pass

    def retrieval_finished(self, retriever: str, outcome: str, evidence_count: int = 0) -> None:
        pass

    def protocol_error(self, reason: str) -> None:
        pass

    def requirement_results(self, statuses: Iterable[str]) -> None:
        pass


NOOP_AGENT_METRICS = NoOpAgentMetrics()


class AgentMetrics:
    def __init__(
        self,
        registry: CollectorRegistry | None = None,
        *,
        cost_estimation_enabled: bool = False,
    ) -> None:
        self.registry = registry or CollectorRegistry(auto_describe=True)
        self._analyses = Counter(
            "agent_service_analyses",
            "Completed Agent analyses by bounded outcome",
            ("outcome",),
            registry=self.registry,
        )
        self._analysis_duration = Histogram(
            "agent_service_analysis_duration_seconds",
            "Whole Agent analysis duration by bounded outcome",
            ("outcome",),
            buckets=(0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 40, 60),
            registry=self.registry,
        )
        self._in_progress = Gauge(
            "agent_service_analyses_in_progress",
            "Authorized Agent analyses currently in progress",
            registry=self.registry,
        )
        self._authentication_failures = Counter(
            "agent_service_authentication_failures",
            "Rejected requests with a missing or invalid internal service token",
            registry=self.registry,
        )
        self._capacity_limit = Gauge(
            "agent_service_analysis_capacity_limit",
            "Maximum concurrent authorized Agent analyses for this process",
            registry=self.registry,
        )
        self._capacity_rejections = Counter(
            "agent_service_analysis_capacity_rejections",
            "Authorized Agent analyses rejected before provider access because capacity was exhausted",
            registry=self.registry,
        )
        self._provider_calls = Counter(
            "agent_service_provider_calls",
            "Chat provider calls by bounded outcome",
            ("outcome",),
            registry=self.registry,
        )
        self._provider_duration = Histogram(
            "agent_service_provider_call_duration_seconds",
            "Chat provider call duration by bounded outcome",
            ("outcome",),
            buckets=(0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 45),
            registry=self.registry,
        )
        self._model_tokens = Counter(
            "agent_service_model_tokens",
            "Provider-reported model token counts; values can be partial when provider_reported=false",
            ("type", "provider_reported"),
            registry=self.registry,
        )
        self._estimated_cost = Counter(
            "agent_service_model_estimated_cost_usd",
            "Estimated chat provider cost in USD using the explicitly configured pricing version",
            registry=self.registry,
        )
        self._cost_estimates = Counter(
            "agent_service_cost_estimates",
            "Chat provider calls with a versioned USD cost estimate",
            registry=self.registry,
        )
        self._cost_estimation_skipped = Counter(
            "agent_service_cost_estimation_skipped",
            "Chat provider calls without a cost estimate by bounded reason",
            ("reason",),
            registry=self.registry,
        )
        self._cost_estimation_enabled = Gauge(
            "agent_service_cost_estimation_enabled",
            "Whether complete versioned model pricing is configured for this Agent process",
            registry=self.registry,
        )
        self._cost_estimation_enabled.set(1 if cost_estimation_enabled else 0)
        self._tool_calls = Counter(
            "agent_service_tool_calls",
            "Tool calls by allowlisted tool name and bounded outcome",
            ("tool", "outcome"),
            registry=self.registry,
        )
        self._tool_duration = Histogram(
            "agent_service_tool_call_duration_seconds",
            "Tool call duration by allowlisted tool name and bounded outcome",
            ("tool", "outcome"),
            buckets=(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20),
            registry=self.registry,
        )
        self._retrieval_calls = Counter(
            "agent_service_retrieval_calls",
            "Resume retrieval calls by implementation kind and bounded outcome",
            ("retriever", "outcome"),
            registry=self.registry,
        )
        self._retrieval_evidence = Counter(
            "agent_service_retrieval_evidence",
            "Evidence chunks returned by successful resume retrieval calls",
            ("retriever",),
            registry=self.registry,
        )
        self._protocol_errors = Counter(
            "agent_service_protocol_errors",
            "Rejected Agent protocol operations by bounded reason",
            ("reason",),
            registry=self.registry,
        )
        self._requirement_results = Counter(
            "agent_service_requirement_results",
            "Final deterministic requirement decisions by status",
            ("status",),
            registry=self.registry,
        )

    def analysis_started(self) -> None:
        self._in_progress.inc()

    def analysis_finished(self, outcome: str, duration_seconds: float) -> None:
        safe_outcome = _bounded(outcome, ANALYSIS_OUTCOMES, "unexpected")
        self._in_progress.dec()
        self._analyses.labels(outcome=safe_outcome).inc()
        self._analysis_duration.labels(outcome=safe_outcome).observe(max(0.0, duration_seconds))

    def authentication_failed(self) -> None:
        self._authentication_failures.inc()

    def configure_capacity_limit(self, limit: int) -> None:
        self._capacity_limit.set(max(0, limit))

    def analysis_capacity_rejected(self) -> None:
        self._capacity_rejections.inc()

    def provider_call_finished(
        self,
        outcome: str,
        duration_seconds: float,
        usage: ModelUsage | None = None,
        estimated_cost_usd: Decimal | None = None,
        cost_skip_reason: str | None = None,
    ) -> None:
        safe_outcome = _bounded(outcome, PROVIDER_OUTCOMES, "unexpected")
        self._provider_calls.labels(outcome=safe_outcome).inc()
        self._provider_duration.labels(outcome=safe_outcome).observe(max(0.0, duration_seconds))
        if usage is None:
            return
        provider_reported = str(usage.provider_reported).lower()
        for token_type, value in (
            ("prompt", usage.prompt_tokens),
            ("completion", usage.completion_tokens),
            ("total", usage.total_tokens),
        ):
            self._model_tokens.labels(
                type=token_type,
                provider_reported=provider_reported,
            ).inc(max(0, value))
        if estimated_cost_usd is not None:
            self._estimated_cost.inc(max(0.0, float(estimated_cost_usd)))
            self._cost_estimates.inc()
        elif cost_skip_reason is not None:
            reason = _bounded(cost_skip_reason, COST_SKIP_REASONS, "other")
            self._cost_estimation_skipped.labels(reason=reason).inc()

    def tool_call_finished(self, name: str, outcome: str, duration_seconds: float) -> None:
        tool = safe_tool_name(name)
        safe_outcome = _bounded(outcome, TOOL_OUTCOMES, "error")
        self._tool_calls.labels(tool=tool, outcome=safe_outcome).inc()
        self._tool_duration.labels(tool=tool, outcome=safe_outcome).observe(
            max(0.0, duration_seconds)
        )

    def retrieval_finished(self, retriever: str, outcome: str, evidence_count: int = 0) -> None:
        kind = _bounded(retriever, RETRIEVER_KINDS, "other")
        safe_outcome = _bounded(outcome, RETRIEVAL_OUTCOMES, "error")
        self._retrieval_calls.labels(retriever=kind, outcome=safe_outcome).inc()
        if safe_outcome == "hit":
            self._retrieval_evidence.labels(retriever=kind).inc(max(0, evidence_count))

    def protocol_error(self, reason: str) -> None:
        safe_reason = _bounded(reason, PROTOCOL_REASONS, "other")
        self._protocol_errors.labels(reason=safe_reason).inc()

    def requirement_results(self, statuses: Iterable[str]) -> None:
        for status in statuses:
            safe_status = _bounded(status, REQUIREMENT_STATUSES, "other")
            self._requirement_results.labels(status=safe_status).inc()
