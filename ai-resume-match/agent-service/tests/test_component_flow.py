import asyncio
import json

import httpx

from agent_service.config import Settings
from agent_service.llm import OpenAICompatibleChatModel
from agent_service.main import create_app


def test_openai_http_adapter_drives_full_agent_api_flow() -> None:
    model_requests: list[dict] = []

    def model_handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        model_requests.append(body)
        tool_messages = [message for message in body["messages"] if message["role"] == "tool"]
        requirements = (
            json.loads(tool_messages[0]["content"])["untrustedData"]["requirements"]
            if tool_messages else []
        )

        if not tool_messages:
            name = "get_job_requirements"
            arguments = {}
        elif len(tool_messages) <= len(requirements):
            requirement = requirements[len(tool_messages) - 1]
            name = "search_resume_evidence"
            arguments = {
                "requirementId": requirement["requirementId"],
                "query": requirement["text"],
                "topK": 2,
            }
        else:
            evidence_by_requirement = {}
            for message in tool_messages:
                content = json.loads(message["content"])
                untrusted = content.get("untrustedData", {})
                requirement_id = untrusted.get("requirementId")
                evidence_ids = [
                    item["evidenceId"]
                    for item in untrusted.get("evidence", [])
                ]
                if requirement_id:
                    evidence_by_requirement[requirement_id] = evidence_ids
            name = "submit_match_report"
            arguments = {
                "requirementAssessments": [
                    {
                        "requirementId": requirement_id,
                        "status": "supported" if evidence_ids else "not_found",
                        "explanation": f"Grounded evidence supports {requirement_id}.",
                        "evidenceIds": sorted(set(evidence_ids)),
                    }
                    for requirement_id, evidence_ids in sorted(evidence_by_requirement.items())
                ],
                "recommendations": ["Run live evals", "Track cost", "Add red-team cases"],
                "interviewQuestions": ["Why tools?", "How retry?", "How evaluate?"],
            }

        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": f"call-{len(tool_messages) + 1}",
                                    "type": "function",
                                    "function": {
                                        "name": name,
                                        "arguments": json.dumps(arguments),
                                    },
                                }
                            ],
                        }
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30},
            },
        )

    async def scenario() -> httpx.Response:
        model = OpenAICompatibleChatModel(
            endpoint="https://model.test/v1/chat/completions",
            api_key="test-key",
            model="component-model",
            timeout_seconds=5,
            transport=httpx.MockTransport(model_handler),
        )
        settings = Settings(
            ai_endpoint="https://model.test/v1/chat/completions",
            ai_api_key="test-key",
            ai_model="component-model",
            service_token="component-token",
        )
        app = create_app(settings, model)
        try:
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app),
                base_url="http://agent.test",
            ) as client:
                return await client.post(
                    "/v1/agent/analyze",
                    headers={"X-Agent-Token": "component-token"},
                    json={
                        "taskId": 701,
                        "resumeText": "Implemented Java RabbitMQ outbox and Python tool calling.",
                        "jobTitle": "AI Agent Engineer",
                        "jobDescription": "Need Java, reliable workflows, tool calling and evaluation.",
                        "skillTags": ["Java", "Tool Calling"],
                        "correlationId": "component-701",
                    },
                )
        finally:
            await model.aclose()

    response = asyncio.run(scenario())

    assert response.status_code == 200
    payload = response.json()
    assert payload["matchScore"] == 50
    assert payload["steps"] == 6
    assert payload["promptVersion"] == "requirement-verified-agent-v3"
    assert payload["retrieverVersion"] == "hashing-blake2b-256-v1"
    assert payload["verifierVersion"] == "conservative-lexical-negation-v2"
    assert payload["modelUsage"]["totalTokens"] == 180
    assert payload["modelUsage"]["providerReported"] is True
    assert [item["name"] for item in payload["toolTrace"]] == [
        "get_job_requirements",
        "search_resume_evidence",
        "search_resume_evidence",
        "search_resume_evidence",
        "search_resume_evidence",
        "submit_match_report",
    ]
    assert len(payload["requirementResults"]) == 4
    assert [item["text"] for item in payload["requirementResults"]] == [
        "Java", "reliable workflows", "Tool Calling", "evaluation"
    ]
    assert "## 证据引用与原文映射" in payload["reportMarkdown"]
    assert "excerpt: Implemented Java RabbitMQ outbox" in payload["reportMarkdown"]
    first_model_context = json.dumps(model_requests[0], ensure_ascii=False)
    assert "Implemented Java RabbitMQ" not in first_model_context
    assert "AI Agent Engineer" not in first_model_context


def test_maps_provider_auth_rejection_to_final_dependency_error() -> None:
    def model_handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": "do-not-forward-provider-body"})

    async def scenario() -> httpx.Response:
        model = OpenAICompatibleChatModel(
            endpoint="https://model.test/v1/chat/completions",
            api_key="bad-key",
            model="component-model",
            timeout_seconds=5,
            transport=httpx.MockTransport(model_handler),
        )
        app = create_app(
            Settings(
                ai_endpoint="https://model.test/v1/chat/completions",
                ai_api_key="bad-key",
                ai_model="component-model",
                service_token="component-token",
            ),
            model,
        )
        try:
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app),
                base_url="http://agent.test",
            ) as client:
                return await client.post(
                    "/v1/agent/analyze",
                    headers={"X-Agent-Token": "component-token"},
                    json={
                        "taskId": 702,
                        "resumeText": "Java project",
                        "jobTitle": "Agent Engineer",
                        "jobDescription": "Need tool calling",
                        "skillTags": ["Java"],
                    },
                )
        finally:
            await model.aclose()

    response = asyncio.run(scenario())

    assert response.status_code == 424
    assert response.json() == {
        "code": "MODEL_PROVIDER_REJECTED",
        "message": "model provider request failed",
        "retryable": False,
        "retryAfterSeconds": None,
    }
    assert "do-not-forward-provider-body" not in response.text


def test_maps_provider_rate_limit_to_retryable_dependency_error() -> None:
    def model_handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            429,
            headers={"Retry-After": "17"},
            json={"error": "provider-rate-limit-detail"},
        )

    async def scenario() -> httpx.Response:
        model = OpenAICompatibleChatModel(
            endpoint="https://model.test/v1/chat/completions",
            api_key="test-key",
            model="component-model",
            timeout_seconds=5,
            transport=httpx.MockTransport(model_handler),
        )
        app = create_app(
            Settings(
                ai_endpoint="https://model.test/v1/chat/completions",
                ai_api_key="test-key",
                ai_model="component-model",
                service_token="component-token",
            ),
            model,
        )
        try:
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app),
                base_url="http://agent.test",
            ) as client:
                return await client.post(
                    "/v1/agent/analyze",
                    headers={"X-Agent-Token": "component-token"},
                    json={
                        "taskId": 703,
                        "resumeText": "Java project",
                        "jobTitle": "Agent Engineer",
                        "jobDescription": "Need tool calling",
                        "skillTags": ["Java"],
                    },
                )
        finally:
            await model.aclose()

    response = asyncio.run(scenario())

    assert response.status_code == 503
    assert response.json() == {
        "code": "MODEL_PROVIDER_UNAVAILABLE",
        "message": "model provider request failed",
        "retryable": True,
        "retryAfterSeconds": 17,
    }
    assert "provider-rate-limit-detail" not in response.text
