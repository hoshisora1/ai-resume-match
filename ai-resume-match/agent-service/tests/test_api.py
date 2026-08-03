import asyncio
import json
import logging

import httpx
import pytest

from agent_service.config import Settings
from agent_service.llm import ModelResponseError
from agent_service.main import create_app
from agent_service.models import AssistantTurn, ModelToolCall
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
                        arguments=json.dumps({"query": "Java", "topK": 1}),
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
                                "matchScore": 78,
                                "coreClaims": [
                                    {
                                        "claim": "The candidate has relevant Java evidence.",
                                        "evidenceIds": ["resume:0"],
                                    }
                                ],
                                "matchedSkills": [
                                    {"claim": "Java", "evidenceIds": ["resume:0"]}
                                ],
                                "skillGaps": ["Evaluation"],
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
        "jobDescription": "Need Java and agent evaluation",
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
    assert response.json()["matchScore"] == 78
    assert response.json()["taskId"] == 5
    assert response.json()["toolTrace"][-1]["name"] == "submit_match_report"


def test_health_does_not_require_authentication() -> None:
    response = asyncio.run(send(create_app(settings(), model()), "GET", "/health"))

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


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

    assert response.status_code == 502
    assert response.json() == {
        "code": "MODEL_RESPONSE_ERROR",
        "message": "invalid OpenAI-compatible response",
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
