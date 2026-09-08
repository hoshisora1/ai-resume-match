import asyncio
import json
import logging
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest

from agent_service.config import Settings
from agent_service.llm import ModelResponseError
from agent_service.main import create_app
from agent_service.models import AssistantTurn, ModelToolCall, ModelUsage
from tests.support import ScriptedModel


def model() -> ScriptedModel:
    return ScriptedModel(
        [
            AssistantTurn(
                toolCalls=[ModelToolCall(callId="1", name="get_job_requirements", arguments="{}")]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="2",
                        name="search_resume_evidence",
                        arguments=json.dumps(
                            {"requirementId": "requirement:0", "query": "Java", "topK": 1}
                        ),
                    )
                ]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="3",
                        name="submit_match_report",
                        arguments=json.dumps(
                            {
                                "requirementAssessments": [
                                    {
                                        "requirementId": "requirement:0",
                                        "status": "supported",
                                        "explanation": "The candidate has relevant Java evidence.",
                                        "evidenceIds": ["resume:0"],
                                    }
                                ],
                                "recommendations": ["Add evals", "Add tools", "Add tracing"],
                                "interviewQuestions": ["Why tools?", "Why tracing?", "How evaluate?"],
                            }
                        ),
                    )
                ]
            ),
        ]
    )


def settings() -> Settings:
    return Settings(
        ai_endpoint="https://example.test/v1/chat/completions",
        ai_api_key="test-key",
        ai_model="test-model",
        service_token="agent-secret",
    )


def payload() -> dict[str, object]:
    return {
        "taskId": 5,
        "resumeText": "Java Spring Boot project",
        "jobTitle": "Agent Engineer",
        "jobDescription": "Need Java.",
        "skillTags": ["Java"],
        "correlationId": "api-test-5",
    }


async def send(
    app,
    method: str,
    path: str,
    *,
    headers: dict[str, str] | None = None,
    json_body: dict[str, object] | None = None,
) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.request(method, path, headers=headers, json=json_body)


def test_rejects_missing_service_token() -> None:
    response = asyncio.run(
        send(create_app(settings(), model()), "POST", "/v1/agent/analyze", json_body=payload())
    )

    assert response.status_code == 401


def test_returns_camel_case_grounded_response() -> None:
    response = asyncio.run(
        send(
            create_app(settings(), model()),
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=payload(),
        )
    )

    assert response.status_code == 200
    assert response.json()["matchScore"] == 100
    assert response.json()["taskId"] == 5
    assert response.json()["promptVersion"] == "requirement-verified-agent-v3"
    assert response.json()["verifierVersion"] == "conservative-lexical-negation-v2"
    assert response.json()["traceId"] is None
    assert response.json()["runMetadata"]["schemaVersion"] == "agent-run-v1"
    assert (
        response.json()["runMetadata"]["requestSchemaVersion"]
        == "agent-analysis-request-v1"
    )
    assert response.json()["runMetadata"]["chatProviderCalls"] == 3
    assert response.json()["runMetadata"]["inputFingerprint"]
    assert response.json()["runMetadata"]["contextCharsSent"] > 0
    assert response.json()["requirementResults"][0]["status"] == "supported"
    assert response.json()["structuredReport"]["schemaVersion"] == "match-report-v2"
    assert response.json()["structuredReport"]["matchScore"] == 100
    assert response.json()["structuredReport"]["evidence"][0]["evidenceId"] == "resume:0"
    assert response.json()["toolTrace"][-1]["name"] == "submit_match_report"
    assert response.json()["modelUsage"]["estimatedCostUsd"] is None
    assert response.json()["modelUsage"]["pricingVersion"] is None


def test_returns_versioned_cost_estimate_and_metric_when_pricing_is_configured() -> None:
    class UsageReportingModel:
        def __init__(self) -> None:
            self.delegate = model()

        @property
        def model_name(self) -> str:
            return self.delegate.model_name

        async def complete(self, messages, tools):
            turn = await self.delegate.complete(messages, tools)
            return turn.model_copy(
                update={
                    "usage": ModelUsage(
                        promptTokens=100,
                        completionTokens=20,
                        totalTokens=120,
                        providerReported=True,
                    )
                }
            )

    priced_settings = replace(
        settings(),
        model_pricing_version="test-price-2026-08-01",
        model_input_cost_usd_per_million_tokens=Decimal("1"),
        model_output_cost_usd_per_million_tokens=Decimal("2"),
    )
    app = create_app(priced_settings, UsageReportingModel())

    response = asyncio.run(
        send(
            app,
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=payload(),
        )
    )
    metrics = asyncio.run(send(app, "GET", "/metrics"))

    assert response.status_code == 200
    assert response.json()["modelUsage"] == {
        "promptTokens": 300,
        "completionTokens": 60,
        "totalTokens": 360,
        "providerReported": True,
        "estimatedCostUsd": "0.00042000",
        "pricingVersion": "test-price-2026-08-01",
    }
    assert "agent_service_model_estimated_cost_usd_total" in metrics.text
    assert "agent_service_cost_estimates_total 3.0" in metrics.text
    assert "agent_service_cost_estimation_enabled 1.0" in metrics.text
    assert "agent_service_cost_estimation_skipped_total{" not in metrics.text


def test_pricing_enabled_without_complete_usage_records_coverage_gap() -> None:
    priced_settings = replace(
        settings(),
        model_pricing_version="test-price-2026-08-01",
        model_input_cost_usd_per_million_tokens=Decimal("1"),
        model_output_cost_usd_per_million_tokens=Decimal("2"),
    )
    app = create_app(priced_settings, model())

    response = asyncio.run(
        send(
            app,
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=payload(),
        )
    )
    metrics = asyncio.run(send(app, "GET", "/metrics"))

    assert response.status_code == 200
    assert response.json()["modelUsage"]["providerReported"] is False
    assert response.json()["modelUsage"]["estimatedCostUsd"] is None
    assert response.json()["modelUsage"]["pricingVersion"] is None
    assert "agent_service_cost_estimation_enabled 1.0" in metrics.text
    assert (
        'agent_service_cost_estimation_skipped_total{reason="usage_not_reported"} 3.0'
        in metrics.text
    )
    assert "agent_service_cost_estimates_total 0.0" in metrics.text


def test_success_logs_omit_resume_and_job_bodies(caplog: pytest.LogCaptureFixture) -> None:
    request = payload()
    request["resumeText"] = "Java Spring Boot SECRET_RESUME_BODY_CANARY"
    request["jobDescription"] = "Need Java SECRET_JD_BODY_CANARY"
    caplog.set_level(logging.INFO, logger="agent_service")

    response = asyncio.run(
        send(
            create_app(settings(), model()),
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=request,
        )
    )

    assert response.status_code == 200
    assert "event=agent_analysis_succeeded" in caplog.text
    assert "SECRET_RESUME_BODY_CANARY" not in caplog.text
    assert "SECRET_JD_BODY_CANARY" not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


def test_hybrid_mode_reports_versioned_retriever() -> None:
    class FakeEmbeddingClient:
        version = "api-fake-v1"

        def embed(self, texts: list[str]) -> list[list[float]]:
            return [
                [1.0, 0.0]
                if "java" in text.casefold()
                else [0.0, 1.0]
                for text in texts
            ]

    hybrid_settings = Settings(
        ai_endpoint="https://example.test/v1/chat/completions",
        ai_api_key="test-key",
        ai_model="test-model",
        service_token="agent-secret",
        retriever_mode="hybrid",
        embedding_endpoint="https://example.test/v1/embeddings",
        embedding_api_key="test-key",
        embedding_model="test-embedding",
        embedding_timeout_seconds=10,
    )

    response = asyncio.run(
        send(
            create_app(hybrid_settings, model(), FakeEmbeddingClient()),
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=payload(),
        )
    )

    assert response.status_code == 200
    assert response.json()["retrieverVersion"] == (
        "hybrid-rrf-v1+dense-cosine-v1+api-fake-v1"
    )


def test_health_does_not_require_authentication() -> None:
    response = asyncio.run(send(create_app(settings(), model()), "GET", "/health"))

    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert response.json()["costEstimation"] == "disabled"
    assert response.json()["tracing"] == "disabled"
    assert response.json()["capacityLimit"] == "4"


def test_metrics_endpoint_exposes_agent_runtime_without_document_content() -> None:
    request = payload()
    request["resumeText"] = "Java Spring Boot SECRET_RESUME_METRICS_CANARY"
    request["jobDescription"] = "Need Java SECRET_JD_METRICS_CANARY"
    app = create_app(settings(), model())

    analysis = asyncio.run(
        send(
            app,
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=request,
        )
    )
    response = asyncio.run(send(app, "GET", "/metrics"))

    assert analysis.status_code == 200
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert 'agent_service_analyses_total{outcome="success"} 1.0' in response.text
    assert 'agent_service_provider_calls_total{outcome="success"} 3.0' in response.text
    assert (
        'agent_service_retrieval_calls_total{outcome="hit",retriever="hashing"} 1.0'
        in response.text
    )
    assert 'agent_service_requirement_results_total{status="partial"} 1.0' in response.text
    assert (
        'agent_service_cost_estimation_skipped_total{reason="pricing_not_configured"} 3.0'
        in response.text
    )
    assert "SECRET_RESUME_METRICS_CANARY" not in response.text
    assert "SECRET_JD_METRICS_CANARY" not in response.text
    assert "api-test-5" not in response.text


def test_rejects_log_injection_in_correlation_id() -> None:
    invalid = payload()
    invalid["correlationId"] = "safe-id\nforged-log-entry"

    response = asyncio.run(
        send(
            create_app(settings(), model()),
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=invalid,
        )
    )

    assert response.status_code == 422


def test_model_failure_log_omits_sensitive_exception_details(caplog: pytest.LogCaptureFixture) -> None:
    secret = "SECRET_RESUME_DERIVED_MODEL_OUTPUT"

    class SensitiveFailureModel:
        model_name = "test-model"

        async def complete(self, *_args, **_kwargs):
            try:
                raise ValueError(secret)
            except ValueError as exc:
                raise ModelResponseError("invalid OpenAI-compatible response") from exc

    caplog.set_level(logging.ERROR, logger="agent_service")
    response = asyncio.run(
        send(
            create_app(settings(), SensitiveFailureModel()),
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=payload(),
        )
    )

    assert response.status_code == 422
    assert response.json() == {
        "code": "MODEL_RESPONSE_ERROR",
        "message": "invalid OpenAI-compatible response",
        "retryable": False,
        "retryAfterSeconds": None,
    }
    assert "exceptionType=ModelResponseError" in caplog.text
    assert secret not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


def test_lifespan_rejects_missing_required_configuration() -> None:
    invalid_settings = Settings(
        ai_endpoint="https://example.test/v1/chat/completions",
        ai_api_key="",
        ai_model="test-model",
        service_token="agent-secret",
    )
    app = create_app(invalid_settings, model())

    async def start_app() -> None:
        async with app.router.lifespan_context(app):
            pass

    with pytest.raises(RuntimeError, match="AI_API_KEY"):
        asyncio.run(start_app())


def test_protocol_failure_is_explicitly_final() -> None:
    invalid_protocol_model = ScriptedModel([AssistantTurn(content="plain text is not accepted")])
    strict_settings = Settings(
        ai_endpoint="https://example.test/v1/chat/completions",
        ai_api_key="test-key",
        ai_model="test-model",
        service_token="agent-secret",
        max_protocol_errors=0,
    )

    app = create_app(strict_settings, invalid_protocol_model)
    response = asyncio.run(
        send(
            app,
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=payload(),
        )
    )

    assert response.status_code == 422
    assert response.json() == {
        "code": "AGENT_PROTOCOL_ERROR",
        "message": "model did not use submit_match_report",
        "retryable": False,
        "retryAfterSeconds": None,
    }
    metrics = asyncio.run(send(app, "GET", "/metrics"))
    assert 'agent_service_analyses_total{outcome="protocol_error"} 1.0' in metrics.text
    assert 'agent_service_protocol_errors_total{reason="missing_tool_call"} 1.0' in metrics.text


def test_analysis_deadline_is_explicitly_retryable() -> None:
    class SlowModel:
        model_name = "slow-test-model"

        async def complete(self, *_args, **_kwargs):
            await asyncio.sleep(2)
            raise AssertionError("analysis deadline should cancel the model call")

    deadline_settings = Settings(
        ai_endpoint="https://example.test/v1/chat/completions",
        ai_api_key="test-key",
        ai_model="slow-test-model",
        service_token="agent-secret",
        model_timeout_seconds=1,
        analysis_timeout_seconds=1.1,
    )

    response = asyncio.run(
        send(
            create_app(deadline_settings, SlowModel()),
            "POST",
            "/v1/agent/analyze",
            headers={"X-Agent-Token": "agent-secret"},
            json_body=payload(),
        )
    )

    assert response.status_code == 504
    assert response.json() == {
        "code": "AGENT_DEADLINE_EXCEEDED",
        "message": "agent analysis deadline exceeded",
        "retryable": True,
        "retryAfterSeconds": None,
    }


def test_cancelled_request_closes_in_progress_and_provider_metrics() -> None:
    class BlockingModel:
        model_name = "blocking-test-model"

        def __init__(self) -> None:
            self.started = asyncio.Event()

        async def complete(self, *_args, **_kwargs):
            self.started.set()
            await asyncio.Event().wait()

    async def scenario() -> str:
        blocking_model = BlockingModel()
        app = create_app(settings(), blocking_model)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            request = asyncio.create_task(
                client.post(
                    "/v1/agent/analyze",
                    headers={"X-Agent-Token": "agent-secret"},
                    json=payload(),
                )
            )
            await asyncio.wait_for(blocking_model.started.wait(), timeout=1)
            request.cancel()
            with pytest.raises(asyncio.CancelledError):
                await request
            return (await client.get("/metrics")).text

    metrics = asyncio.run(scenario())

    assert "agent_service_analyses_in_progress 0.0" in metrics
    assert 'agent_service_analyses_total{outcome="cancelled"} 1.0' in metrics
    assert 'agent_service_provider_calls_total{outcome="cancelled"} 1.0' in metrics


def test_capacity_limit_rejects_before_a_second_provider_call_and_recovers_on_cancel() -> None:
    class RecoveringModel:
        model_name = "blocking-test-model"

        def __init__(self) -> None:
            self.started = asyncio.Event()
            self.calls = 0
            self.delegate = model()

        async def complete(self, *_args, **_kwargs):
            self.calls += 1
            if self.calls == 1:
                self.started.set()
                await asyncio.Event().wait()
            return await self.delegate.complete(*_args, **_kwargs)

    async def scenario() -> tuple[httpx.Response, httpx.Response, str, int, int]:
        recovering_model = RecoveringModel()
        capacity_settings = replace(
            settings(),
            max_concurrent_analyses=1,
            capacity_acquire_timeout_seconds=0.01,
        )
        app = create_app(capacity_settings, recovering_model)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            first = asyncio.create_task(
                client.post(
                    "/v1/agent/analyze",
                    headers={"X-Agent-Token": "agent-secret"},
                    json=payload(),
                )
            )
            await asyncio.wait_for(recovering_model.started.wait(), timeout=1)
            second = await client.post(
                "/v1/agent/analyze",
                headers={"X-Agent-Token": "agent-secret"},
                json={**payload(), "taskId": 6},
            )
            calls_when_rejected = recovering_model.calls
            first.cancel()
            with pytest.raises(asyncio.CancelledError):
                await first
            third = await client.post(
                "/v1/agent/analyze",
                headers={"X-Agent-Token": "agent-secret"},
                json={**payload(), "taskId": 7},
            )
            metrics = (await client.get("/metrics")).text
            return second, third, metrics, calls_when_rejected, recovering_model.calls

    response, recovered, metrics, calls_when_rejected, provider_calls = asyncio.run(scenario())

    assert response.status_code == 503
    assert response.json() == {
        "code": "AGENT_CAPACITY_EXHAUSTED",
        "message": "agent analysis capacity is temporarily exhausted",
        "retryable": True,
        "retryAfterSeconds": 1,
    }
    assert calls_when_rejected == 1
    assert recovered.status_code == 200
    assert provider_calls == 4
    assert "agent_service_analysis_capacity_limit 1.0" in metrics
    assert "agent_service_analysis_capacity_rejections_total 1.0" in metrics
    assert "agent_service_analyses_in_progress 0.0" in metrics
