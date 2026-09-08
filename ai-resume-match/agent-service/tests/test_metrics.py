from decimal import Decimal

from prometheus_client import CollectorRegistry, generate_latest

from agent_service.metrics import AgentMetrics
from agent_service.models import ModelUsage


def test_exposes_bounded_runtime_metrics_without_sensitive_labels() -> None:
    registry = CollectorRegistry(auto_describe=True)
    metrics = AgentMetrics(registry, cost_estimation_enabled=True)

    metrics.analysis_started()
    metrics.provider_call_finished(
        "success",
        0.25,
        ModelUsage(
            promptTokens=12,
            completionTokens=3,
            totalTokens=15,
            providerReported=True,
        ),
        Decimal("0.00027"),
    )
    metrics.tool_call_finished("search_resume_evidence", "success", 0.01)
    metrics.retrieval_finished("hashing", "hit", 2)
    metrics.protocol_error("tool_denied")
    metrics.requirement_results(["supported", "not_found"])
    metrics.authentication_failed()
    metrics.analysis_finished("success", 0.5)

    body = generate_latest(registry).decode("utf-8")

    assert 'agent_service_analyses_total{outcome="success"} 1.0' in body
    assert "agent_service_analyses_in_progress 0.0" in body
    assert 'agent_service_provider_calls_total{outcome="success"} 1.0' in body
    assert (
        'agent_service_model_tokens_total{provider_reported="true",type="total"} 15.0'
        in body
    )
    assert (
        'agent_service_tool_calls_total{outcome="success",tool="search_resume_evidence"} 1.0'
        in body
    )
    assert (
        'agent_service_retrieval_calls_total{outcome="hit",retriever="hashing"} 1.0'
        in body
    )
    assert 'agent_service_retrieval_evidence_total{retriever="hashing"} 2.0' in body
    assert 'agent_service_protocol_errors_total{reason="tool_denied"} 1.0' in body
    assert 'agent_service_requirement_results_total{status="supported"} 1.0' in body
    assert "agent_service_authentication_failures_total 1.0" in body
    assert "agent_service_model_estimated_cost_usd_total 0.00027" in body
    assert "agent_service_cost_estimates_total 1.0" in body
    assert "agent_service_cost_estimation_enabled 1.0" in body
    assert "SECRET_RESUME_BODY_CANARY" not in body
    assert "taskId" not in body
    assert "correlationId" not in body


def test_unknown_dynamic_values_are_collapsed_to_bounded_fallbacks() -> None:
    registry = CollectorRegistry(auto_describe=True)
    metrics = AgentMetrics(registry)

    metrics.tool_call_finished("SECRET_DYNAMIC_TOOL", "invented", 0)
    metrics.retrieval_finished("SECRET_DYNAMIC_RETRIEVER", "invented")
    metrics.protocol_error("SECRET_DYNAMIC_REASON")
    metrics.requirement_results(["SECRET_DYNAMIC_STATUS"])
    metrics.provider_call_finished(
        "success",
        0,
        ModelUsage(providerReported=False),
        cost_skip_reason="SECRET_DYNAMIC_REASON",
    )

    body = generate_latest(registry).decode("utf-8")

    assert 'agent_service_tool_calls_total{outcome="error",tool="unknown"} 1.0' in body
    assert 'agent_service_retrieval_calls_total{outcome="error",retriever="other"} 1.0' in body
    assert 'agent_service_protocol_errors_total{reason="other"} 1.0' in body
    assert 'agent_service_requirement_results_total{status="other"} 1.0' in body
    assert 'agent_service_cost_estimation_skipped_total{reason="other"} 1.0' in body
    assert "SECRET_DYNAMIC" not in body
