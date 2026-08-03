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

        if not tool_messages:
            name = "get_job_requirements"
            arguments = {}
        elif len(tool_messages) == 1:
            name = "search_resume_evidence"
            arguments = {"query": "Java RabbitMQ tool calling", "topK": 2}
        else:
            evidence_ids = []
            for message in tool_messages:
                content = json.loads(message["content"])
                evidence_ids.extend(
                    item["evidenceId"]
                    for item in content.get("untrustedData", {}).get("evidence", [])
                )
            name = "submit_match_report"
            cited = sorted(set(evidence_ids))
            arguments = {
                "matchScore": 84,
                "coreClaims": [
                    {
                        "claim": "The candidate has grounded backend and tool-calling implementation evidence.",
                        "evidenceIds": cited,
                    }
                ],
                "matchedSkills": [
                    {"claim": "Java", "evidenceIds": cited},
                    {"claim": "RabbitMQ", "evidenceIds": cited},
                    {"claim": "Tool Calling", "evidenceIds": cited},
                ],
                "skillGaps": ["Production-scale evaluation data"],
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
    assert payload["matchScore"] == 84
    assert payload["steps"] == 3
    assert payload["modelUsage"]["totalTokens"] == 90
    assert payload["modelUsage"]["providerReported"] is True
    assert [item["name"] for item in payload["toolTrace"]] == [
        "get_job_requirements",
        "search_resume_evidence",
        "submit_match_report",
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
    }
    assert "do-not-forward-provider-body" not in response.text
