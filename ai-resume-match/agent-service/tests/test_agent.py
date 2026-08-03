import asyncio
import json

import pytest

from agent_service.agent import AgentProtocolError, BoundedToolAgent, serialized_context_chars
from agent_service.models import AnalysisRequest, AssistantTurn, ModelToolCall, ModelUsage
from agent_service.tools import ToolRegistry
from tests.support import ScriptedModel


def analysis_request() -> AnalysisRequest:
    return AnalysisRequest(
        taskId=42,
        resumeText=(
            "Implemented Java Spring Boot APIs and Redis caching.\n\n"
            "Built RabbitMQ outbox retries and Micrometer metrics."
        ),
        jobTitle="AI Agent Engineer",
        jobDescription="Need Java, reliable tool workflows, RAG, evaluation and observability.",
        skillTags=["Java", "RAG", "Observability"],
        correlationId="agent-test-42",
    )


def test_runs_grounded_tool_loop_and_submits_report() -> None:
    model = ScriptedModel(
        [
            AssistantTurn(
                toolCalls=[ModelToolCall(callId="call-1", name="get_job_requirements", arguments="{}")]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-2",
                        name="search_resume_evidence",
                        arguments=json.dumps({"query": "Java Redis RabbitMQ observability", "topK": 2}),
                    )
                ]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-3",
                        name="submit_match_report",
                        arguments=json.dumps(
                            {
                                "matchScore": 82,
                                "coreClaims": [
                                    {
                                        "claim": "Backend reliability work is directly evidenced.",
                                        "evidenceIds": ["resume:0", "resume:1"],
                                    }
                                ],
                                "matchedSkills": [
                                    {"claim": "Java and Redis", "evidenceIds": ["resume:0"]},
                                    {"claim": "RabbitMQ", "evidenceIds": ["resume:1"]},
                                ],
                                "skillGaps": ["Agent evaluation"],
                                "recommendations": [
                                    "Add a tool-calling loop",
                                    "Create an evaluation dataset",
                                    "Track task cost and latency",
                                ],
                                "interviewQuestions": [
                                    "Why bound the loop?",
                                    "How are tool arguments validated?",
                                    "How do retries stay idempotent?",
                                ],
                            }
                        ),
                    )
                ]
            ),
        ]
    )
    agent = BoundedToolAgent(
        model=model,
        registry=ToolRegistry(),
        max_steps=6,
        max_tool_calls=8,
        max_protocol_errors=2,
    )

    result = asyncio.run(agent.run(analysis_request()))

    assert result.match_score == 82
    assert result.steps == 3
    assert [item.name for item in result.tool_trace] == [
        "get_job_requirements",
        "search_resume_evidence",
        "submit_match_report",
    ]
    assert "## 证据引用与原文映射" in result.report_markdown
    assert "Backend reliability work is directly evidenced" in result.report_markdown
    assert "excerpt: Implemented Java Spring Boot APIs and Redis caching" in result.report_markdown
    assert "resume:0" in result.report_markdown
    assert result.model_usage.provider_reported is False
    first_prompt = json.dumps(model.messages_seen[0], ensure_ascii=False)
    assert "Implemented Java Spring Boot" not in first_prompt
    assert "Need Java, reliable tool workflows" not in first_prompt
    assert "AI Agent Engineer" not in first_prompt
    assert "Observability" not in first_prompt


def test_fails_when_model_never_calls_tools() -> None:
    model = ScriptedModel([AssistantTurn(content="plain answer")] * 3)
    agent = BoundedToolAgent(
        model=model,
        registry=ToolRegistry(),
        max_steps=3,
        max_tool_calls=5,
        max_protocol_errors=1,
    )

    with pytest.raises(AgentProtocolError, match="did not use"):
        asyncio.run(agent.run(analysis_request()))


def test_finishes_with_conservative_report_when_retrieval_is_empty() -> None:
    model = ScriptedModel(
        [
            AssistantTurn(
                toolCalls=[ModelToolCall(callId="call-1", name="get_job_requirements", arguments="{}")]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-2",
                        name="search_resume_evidence",
                        arguments=json.dumps({"query": "COBOL mainframe", "topK": 2}),
                    )
                ]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-3",
                        name="submit_match_report",
                        arguments=json.dumps(
                            {
                                "matchScore": 20,
                                "coreClaims": [],
                                "matchedSkills": [],
                                "skillGaps": ["No relevant resume evidence was retrieved"],
                                "recommendations": [
                                    "Add relevant project evidence",
                                    "Describe responsibilities",
                                    "Include verifiable outcomes",
                                ],
                                "interviewQuestions": [
                                    "What relevant work can you demonstrate?",
                                    "How would you learn the missing stack?",
                                    "Which outcomes can be verified?",
                                ],
                            }
                        ),
                    )
                ]
            ),
        ]
    )
    agent = BoundedToolAgent(
        model=model,
        registry=ToolRegistry(),
        max_steps=6,
        max_tool_calls=8,
        max_protocol_errors=2,
    )

    result = asyncio.run(agent.run(analysis_request()))

    assert result.match_score == 20
    assert "未形成有证据支持的正向结论" in result.report_markdown
    assert "无被引用的简历证据" in result.report_markdown


def test_stops_when_cumulative_provider_token_budget_is_exceeded() -> None:
    model = ScriptedModel(
        [
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(callId="call-1", name="get_job_requirements", arguments="{}")
                ],
                usage=ModelUsage(
                    promptTokens=45,
                    completionTokens=15,
                    totalTokens=60,
                    providerReported=True,
                ),
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-2",
                        name="search_resume_evidence",
                        arguments=json.dumps({"query": "Java", "topK": 1}),
                    )
                ],
                usage=ModelUsage(
                    promptTokens=40,
                    completionTokens=10,
                    totalTokens=50,
                    providerReported=True,
                ),
            ),
        ]
    )
    agent = BoundedToolAgent(
        model=model,
        registry=ToolRegistry(),
        max_steps=6,
        max_tool_calls=8,
        max_protocol_errors=2,
        max_total_tokens=100,
    )

    with pytest.raises(AgentProtocolError, match="token budget exceeded"):
        asyncio.run(agent.run(analysis_request()))

    assert len(model.messages_seen) == 2


def test_stops_before_sending_context_that_exceeds_cumulative_char_budget() -> None:
    registry = ToolRegistry()
    probe_model = ScriptedModel([AssistantTurn(content="plain answer")])
    probe_agent = BoundedToolAgent(
        model=probe_model,
        registry=registry,
        max_steps=1,
        max_tool_calls=8,
        max_protocol_errors=2,
        max_context_chars=1_000_000,
    )
    with pytest.raises(AgentProtocolError, match="step limit"):
        asyncio.run(probe_agent.run(analysis_request()))
    first_context_chars = serialized_context_chars(
        probe_model.messages_seen[0],
        registry.definitions(),
    )

    constrained_model = ScriptedModel(
        [AssistantTurn(content="plain answer"), AssistantTurn(content="plain answer")]
    )
    constrained_agent = BoundedToolAgent(
        model=constrained_model,
        registry=registry,
        max_steps=3,
        max_tool_calls=8,
        max_protocol_errors=2,
        max_context_chars=first_context_chars * 2,
    )

    with pytest.raises(AgentProtocolError, match="context character budget exceeded"):
        asyncio.run(constrained_agent.run(analysis_request()))

    assert len(constrained_model.messages_seen) == 1
