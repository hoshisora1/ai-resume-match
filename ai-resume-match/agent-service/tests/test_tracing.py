import asyncio
import json
from dataclasses import replace

import httpx
import pytest
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from agent_service.config import Settings
from agent_service.embeddings import OpenAICompatibleEmbeddingClient
from agent_service.llm import OpenAICompatibleChatModel
from agent_service.main import create_app
from agent_service.models import AssistantTurn, ModelToolCall
from agent_service.tracing import AgentTracing
from tests.support import ScriptedModel


TRACE_ID = "11111111111111111111111111111111"
PARENT_SPAN_ID = "2222222222222222"
TRACEPARENT = f"00-{TRACE_ID}-{PARENT_SPAN_ID}-01"


def settings() -> Settings:
    return Settings(
        ai_endpoint="https://provider.test/v1/chat/completions",
        ai_api_key="test-key",
        ai_model="test-model",
        service_token="agent-secret",
        tracing_enabled=True,
        tracing_sample_probability=1.0,
        otel_exporter_otlp_traces_endpoint="http://tempo.test:4318/v1/traces",
        otel_service_name="ai-resume-match-agent-test",
        otel_deployment_environment="test",
    )


def scripted_model() -> ScriptedModel:
    return ScriptedModel(
        [
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(callId="1", name="get_job_requirements", arguments="{}")
                ]
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
                                        "explanation": "Java evidence is present.",
                                        "evidenceIds": ["resume:0"],
                                    }
                                ],
                                "recommendations": ["one", "two", "three"],
                                "interviewQuestions": ["one?", "two?", "three?"],
                            }
                        ),
                    )
                ]
            ),
        ]
    )


async def send(app, path: str, *, headers: dict[str, str]) -> httpx.Response:
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://agent.test",
    ) as client:
        return await client.post(
            path,
            headers=headers,
            json={
                "taskId": 7,
                "resumeText": "Java SECRET_RESUME_TRACE_CANARY",
                "jobTitle": "Agent Engineer",
                "jobDescription": "Need Java SECRET_JD_TRACE_CANARY",
                "skillTags": ["Java"],
                "correlationId": "trace-test-7",
            },
        )


def test_fastapi_continues_w3c_trace_and_returns_queryable_trace_id() -> None:
    exporter = InMemorySpanExporter()
    tracing = AgentTracing.create(settings(), span_exporter=exporter)
    app = create_app(settings(), scripted_model(), tracing=tracing)

    response = asyncio.run(
        send(
            app,
            "/v1/agent/analyze",
            headers={
                "X-Agent-Token": "agent-secret",
                "traceparent": TRACEPARENT,
            },
        )
    )

    assert response.status_code == 200
    assert response.json()["traceId"] == TRACE_ID
    spans = exporter.get_finished_spans()
    assert spans
    assert all(format(span.context.trace_id, "032x") == TRACE_ID for span in spans)
    server_spans = [span for span in spans if span.kind.name == "SERVER"]
    assert len(server_spans) == 1
    assert format(server_spans[0].parent.span_id, "016x") == PARENT_SPAN_ID
    rendered = repr([(span.name, dict(span.attributes)) for span in spans])
    assert "SECRET_RESUME_TRACE_CANARY" not in rendered
    assert "SECRET_JD_TRACE_CANARY" not in rendered
    assert "agent-secret" not in rendered
    tracing.shutdown()


def test_httpx_clients_propagate_traceparent_without_capturing_payloads() -> None:
    exporter = InMemorySpanExporter()
    tracing = AgentTracing.create(settings(), span_exporter=exporter)
    received_traceparents: list[str] = []

    def chat_handler(request: httpx.Request) -> httpx.Response:
        received_traceparents.append(request.headers["traceparent"])
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "ok", "tool_calls": []}}],
                "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2,
                },
            },
        )

    def embedding_handler(request: httpx.Request) -> httpx.Response:
        received_traceparents.append(request.headers["traceparent"])
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [1.0, 0.0]}]})

    chat = OpenAICompatibleChatModel(
        endpoint="https://provider.test/v1/chat/completions",
        api_key="SECRET_PROVIDER_KEY",
        model="test-model",
        timeout_seconds=1,
        transport=httpx.MockTransport(chat_handler),
        tracing=tracing,
    )
    embeddings = OpenAICompatibleEmbeddingClient(
        endpoint="https://provider.test/v1/embeddings",
        api_key="SECRET_EMBEDDING_KEY",
        model="test-embedding",
        timeout_seconds=1,
        transport=httpx.MockTransport(embedding_handler),
        tracing=tracing,
    )

    async def invoke() -> str:
        with tracing.tracer.start_as_current_span("agent-analysis") as parent:
            await chat.complete(
                [{"role": "user", "content": "SECRET_MODEL_PAYLOAD_CANARY"}],
                [],
            )
            await asyncio.to_thread(
                embeddings.embed,
                ["SECRET_EMBEDDING_PAYLOAD_CANARY"],
            )
            return format(parent.get_span_context().trace_id, "032x")

    trace_id = asyncio.run(invoke())
    asyncio.run(chat.aclose())
    embeddings.close()

    assert len(received_traceparents) == 2
    assert all(value.split("-")[1] == trace_id for value in received_traceparents)
    spans = exporter.get_finished_spans()
    client_spans = [span for span in spans if span.kind.name == "CLIENT"]
    assert len(client_spans) == 2
    rendered = repr([(span.name, dict(span.attributes)) for span in spans])
    for secret in (
        "SECRET_PROVIDER_KEY",
        "SECRET_EMBEDDING_KEY",
        "SECRET_MODEL_PAYLOAD_CANARY",
        "SECRET_EMBEDDING_PAYLOAD_CANARY",
    ):
        assert secret not in rendered
    tracing.shutdown()


def test_health_and_metrics_do_not_create_trace_noise() -> None:
    exporter = InMemorySpanExporter()
    tracing = AgentTracing.create(settings(), span_exporter=exporter)
    app = create_app(settings(), scripted_model(), tracing=tracing)

    async def probe() -> list[int]:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://agent.test",
        ) as client:
            health = await client.get("/health")
            metrics = await client.get("/metrics")
            return [health.status_code, metrics.status_code]

    assert asyncio.run(probe()) == [200, 200]
    assert exporter.get_finished_spans() == ()
    tracing.shutdown()


@pytest.mark.parametrize(
    ("updates", "message"),
    [
        ({"tracing_sample_probability": 1.1}, "OTEL_TRACES_SAMPLER_ARG"),
        ({"otel_export_timeout_seconds": 0}, "TIMEOUT_SECONDS"),
        ({"otel_service_name": "unsafe service"}, "OTEL_SERVICE_NAME"),
        ({"otel_deployment_environment": ""}, "OTEL_DEPLOYMENT_ENVIRONMENT"),
        (
            {"otel_exporter_otlp_traces_endpoint": "http://user:pass@tempo.test/v1/traces"},
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
        ),
        (
            {"otel_exporter_otlp_traces_endpoint": "http://tempo.test/v1/traces?token=secret"},
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
        ),
        (
            {"otel_exporter_otlp_traces_endpoint": "http://tempo.test:bad/v1/traces"},
            "valid port",
        ),
    ],
)
def test_rejects_unsafe_tracing_configuration(
    updates: dict[str, object],
    message: str,
) -> None:
    with pytest.raises(RuntimeError, match=message):
        replace(settings(), **updates).validate_tracing()


def test_disabled_tracing_does_not_create_an_exporter() -> None:
    tracing = AgentTracing.create(replace(settings(), tracing_enabled=False))

    assert tracing.enabled is False
    assert tracing.tracer_provider is None


def test_rejects_invalid_boolean_environment_value(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_TRACING_ENABLED", "sometimes")

    with pytest.raises(RuntimeError, match="AGENT_TRACING_ENABLED must be true or false"):
        Settings.from_env()
