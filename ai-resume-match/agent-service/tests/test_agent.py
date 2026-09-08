import asyncio
import json
from threading import Event

import pytest
from pydantic import ValidationError

from agent_service.agent import AgentProtocolError, BoundedToolAgent, serialized_context_chars
from agent_service.models import (
    AnalysisRequest,
    AnalysisResponse,
    AssistantTurn,
    ModelToolCall,
    ModelUsage,
)
from agent_service.provenance import analysis_input_fingerprint
from agent_service.tools import ToolRegistry
from tests.support import ScriptedModel


def analysis_request() -> AnalysisRequest:
    return AnalysisRequest(
        taskId=42,
        resumeText=(
            "Implemented Java Spring Boot APIs and Redis caching.\n\n"
            "Built RabbitMQ outbox retries and Micrometer observability metrics."
        ),
        jobTitle="AI Agent Engineer",
        jobDescription="Need Java, RAG and observability.",
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
                        arguments=json.dumps(
                            {"requirementId": "requirement:0", "query": "Java Redis", "topK": 2}
                        ),
                    ),
                    ModelToolCall(
                        callId="call-3",
                        name="search_resume_evidence",
                        arguments=json.dumps(
                            {"requirementId": "requirement:1", "query": "RAG", "topK": 2}
                        ),
                    ),
                    ModelToolCall(
                        callId="call-4",
                        name="search_resume_evidence",
                        arguments=json.dumps(
                            {"requirementId": "requirement:2", "query": "Micrometer", "topK": 2}
                        ),
                    ),
                ]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-5",
                        name="submit_match_report",
                        arguments=json.dumps(
                            {
                                "requirementAssessments": [
                                    {
                                        "requirementId": "requirement:0",
                                        "status": "supported",
                                        "explanation": "Java and Redis are directly evidenced.",
                                        "evidenceIds": ["resume:0"],
                                    },
                                    {
                                        "requirementId": "requirement:1",
                                        "status": "not_found",
                                        "explanation": "No RAG evidence was found.",
                                        "evidenceIds": [],
                                    },
                                    {
                                        "requirementId": "requirement:2",
                                        "status": "supported",
                                        "explanation": "Micrometer observability is evidenced.",
                                        "evidenceIds": ["resume:1"],
                                    },
                                ],
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

    assert result.match_score == 67
    assert result.steps == 3
    assert [item.name for item in result.tool_trace] == [
        "get_job_requirements",
        "search_resume_evidence",
        "search_resume_evidence",
        "search_resume_evidence",
        "submit_match_report",
    ]
    assert [item.status for item in result.requirement_results] == [
        "supported",
        "not_found",
        "supported",
    ]
    assert "## 岗位要求逐项判定" in result.report_markdown
    assert "## 证据引用与原文映射" in result.report_markdown
    assert "引用片段覆盖该要求的关键术语" in result.report_markdown
    assert "excerpt: Implemented Java Spring Boot APIs and Redis caching" in result.report_markdown
    assert "resume:0" in result.report_markdown
    assert result.structured_report.schema_version == "match-report-v2"
    assert result.structured_report.match_score == 67
    assert result.structured_report.requirements == result.requirement_results
    assert [item.evidence_id for item in result.structured_report.evidence] == [
        "resume:0",
        "resume:1",
    ]
    assert result.structured_report.score_breakdown.model_dump(by_alias=True) == {
        "rawScore": 67,
        "finalScore": 67,
        "totalWeight": 6,
        "supportedWeight": 4,
        "partialWeight": 0,
        "missingWeight": 2,
        "mustHaveCapApplied": False,
    }
    assert result.model_usage.provider_reported is False
    assert result.run_metadata.schema_version == "agent-run-v1"
    assert result.run_metadata.request_schema_version == "agent-analysis-request-v1"
    assert result.run_metadata.agent_runtime_version == "bounded-tool-agent-v1"
    assert result.run_metadata.input_fingerprint == analysis_input_fingerprint(
        analysis_request()
    )
    assert result.run_metadata.chat_provider_calls == result.steps == 3
    assert result.run_metadata.tool_duration_ms == sum(
        item.duration_ms for item in result.tool_trace
    )
    assert result.run_metadata.chat_provider_duration_ms >= 0
    assert result.run_metadata.total_duration_ms >= 0
    assert result.run_metadata.context_chars_sent > 0

    invalid = result.model_dump(by_alias=True)
    invalid["runMetadata"]["toolDurationMs"] += 1
    with pytest.raises(ValidationError, match="tool duration"):
        AnalysisResponse.model_validate(invalid)
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
                        arguments=json.dumps(
                            {"requirementId": "requirement:0", "query": "COBOL mainframe", "topK": 2}
                        ),
                    ),
                    ModelToolCall(
                        callId="call-3",
                        name="search_resume_evidence",
                        arguments=json.dumps(
                            {"requirementId": "requirement:1", "query": "COBOL mainframe", "topK": 2}
                        ),
                    ),
                    ModelToolCall(
                        callId="call-4",
                        name="search_resume_evidence",
                        arguments=json.dumps(
                            {"requirementId": "requirement:2", "query": "COBOL mainframe", "topK": 2}
                        ),
                    ),
                ]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-5",
                        name="submit_match_report",
                        arguments=json.dumps(
                            {
                                "requirementAssessments": [
                                    {
                                        "requirementId": f"requirement:{index}",
                                        "status": "not_found",
                                        "explanation": "No relevant resume evidence was retrieved.",
                                        "evidenceIds": [],
                                    }
                                    for index in range(3)
                                ],
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

    assert result.match_score == 0
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
                        arguments=json.dumps(
                            {"requirementId": "requirement:0", "query": "Java", "topK": 1}
                        ),
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


def test_cancelling_agent_signals_in_flight_retrieval() -> None:
    class BlockingRetriever:
        version = "blocking-test-v1"

        def __init__(self) -> None:
            self.started = Event()
            self.cancelled = Event()

        def search(self, _query: str, _top_k: int) -> list:
            self.started.set()
            self.cancelled.wait(timeout=2)
            return []

        def cancel(self) -> None:
            self.cancelled.set()

    retriever = BlockingRetriever()
    model = ScriptedModel(
        [
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-1",
                        name="get_job_requirements",
                        arguments="{}",
                    )
                ]
            ),
            AssistantTurn(
                toolCalls=[
                    ModelToolCall(
                        callId="call-2",
                        name="search_resume_evidence",
                        arguments=json.dumps(
                            {
                                "requirementId": "requirement:0",
                                "query": "Java",
                                "topK": 1,
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
        retriever_factory=lambda _: retriever,
    )

    with pytest.raises(TimeoutError):
        asyncio.run(asyncio.wait_for(agent.run(analysis_request()), timeout=0.1))

    assert retriever.started.is_set()
    assert retriever.cancelled.is_set()
