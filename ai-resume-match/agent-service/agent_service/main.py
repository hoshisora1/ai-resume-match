from __future__ import annotations

import asyncio
import hmac
import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from functools import partial

import httpx
from fastapi import FastAPI, Header, HTTPException, Response
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from agent_service.agent import AgentProtocolError, BoundedToolAgent
from agent_service.config import Settings
from agent_service.embeddings import (
    EmbeddingResponseError,
    OpenAICompatibleEmbeddingClient,
)
from agent_service.llm import ChatModel, ModelResponseError, OpenAICompatibleChatModel
from agent_service.metrics import AgentMetrics
from agent_service.models import AnalysisRequest, AnalysisResponse
from agent_service.retrieval import EmbeddingClient, HashingRetriever, HybridRetriever
from agent_service.tools import ToolRegistry
from agent_service.tracing import AgentTracing, current_trace_id


log = logging.getLogger("agent_service")


class AgentCapacityExceeded(RuntimeError):
    pass


def analysis_failure_outcome(exc: Exception) -> str:
    if isinstance(exc, AgentProtocolError):
        return "protocol_error"
    if isinstance(exc, ModelResponseError):
        return "model_response_error"
    if isinstance(exc, EmbeddingResponseError):
        return "embedding_provider_error"
    if isinstance(exc, TimeoutError):
        return "deadline"
    if isinstance(exc, httpx.HTTPStatusError):
        status = exc.response.status_code
        return "provider_unavailable" if status in {408, 429} or status >= 500 else "provider_rejected"
    if isinstance(exc, httpx.RequestError):
        return "provider_unavailable"
    return "unexpected"


def error_response(
    *,
    status_code: int,
    code: str,
    message: str,
    retryable: bool,
    retry_after_seconds: int | None = None,
) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "code": code,
            "message": message,
            "retryable": retryable,
            "retryAfterSeconds": retry_after_seconds,
        },
    )


def provider_retry_after_seconds(response: httpx.Response) -> int | None:
    value = response.headers.get("Retry-After", "").strip()
    if not value.isdecimal():
        return None
    return min(int(value), 86_400)


def create_app(
    settings: Settings | None = None,
    model: ChatModel | None = None,
    embedding_client: EmbeddingClient | None = None,
    tracing: AgentTracing | None = None,
) -> FastAPI:
    resolved = settings or Settings.from_env()
    active_tracing = tracing or AgentTracing.create(resolved)
    pricing = resolved.model_pricing()
    metrics = AgentMetrics(cost_estimation_enabled=pricing is not None)
    metrics.configure_capacity_limit(resolved.max_concurrent_analyses)
    analysis_capacity = asyncio.BoundedSemaphore(max(1, resolved.max_concurrent_analyses))
    chat_model = model or OpenAICompatibleChatModel(
        endpoint=resolved.ai_endpoint,
        api_key=resolved.ai_api_key,
        model=resolved.ai_model,
        timeout_seconds=resolved.model_timeout_seconds,
        max_completion_tokens=resolved.max_completion_tokens,
        tracing=active_tracing,
    )
    resolved.validate_retrieval()
    active_embedding_client = embedding_client
    if resolved.retriever_mode == "hybrid":
        active_embedding_client = active_embedding_client or OpenAICompatibleEmbeddingClient(
            endpoint=resolved.embedding_endpoint,
            api_key=resolved.embedding_api_key,
            model=resolved.embedding_model,
            timeout_seconds=resolved.embedding_timeout_seconds,
            batch_size=resolved.embedding_batch_size,
            tracing=active_tracing,
        )
        retriever_factory = partial(
            HybridRetriever,
            embedding_client=active_embedding_client,
            dense_min_similarity=resolved.dense_min_similarity,
        )
    else:
        retriever_factory = HashingRetriever

    agent = BoundedToolAgent(
        model=chat_model,
        registry=ToolRegistry(),
        max_steps=resolved.max_steps,
        max_tool_calls=resolved.max_tool_calls,
        max_protocol_errors=resolved.max_protocol_errors,
        max_total_tokens=resolved.max_total_tokens,
        max_context_chars=resolved.max_context_chars,
        retriever_factory=retriever_factory,
        metrics=metrics,
        pricing=pricing,
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        try:
            resolved.validate_for_analysis()
            yield
        finally:
            close = getattr(chat_model, "aclose", None)
            if close is not None:
                await close()
            if active_embedding_client is not None:
                close_embedding_client = getattr(active_embedding_client, "close", None)
                if close_embedding_client is not None:
                    await asyncio.to_thread(close_embedding_client)
            active_tracing.shutdown()

    app = FastAPI(
        title="AI Resume Match Agent Service",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )

    @app.exception_handler(AgentProtocolError)
    async def handle_protocol_error(_, exc: AgentProtocolError) -> JSONResponse:
        return error_response(
            status_code=422,
            code="AGENT_PROTOCOL_ERROR",
            message=str(exc),
            retryable=False,
        )

    @app.exception_handler(ModelResponseError)
    async def handle_model_response_error(_, exc: ModelResponseError) -> JSONResponse:
        return error_response(
            status_code=422,
            code="MODEL_RESPONSE_ERROR",
            message=str(exc),
            retryable=False,
        )

    @app.exception_handler(EmbeddingResponseError)
    async def handle_embedding_response_error(_, __: EmbeddingResponseError) -> JSONResponse:
        return error_response(
            status_code=502,
            code="EMBEDDING_PROVIDER_INVALID_RESPONSE",
            message="embedding provider returned an invalid response",
            retryable=True,
        )

    @app.exception_handler(TimeoutError)
    async def handle_analysis_timeout(_, __: TimeoutError) -> JSONResponse:
        return error_response(
            status_code=504,
            code="AGENT_DEADLINE_EXCEEDED",
            message="agent analysis deadline exceeded",
            retryable=True,
        )

    @app.exception_handler(AgentCapacityExceeded)
    async def handle_agent_capacity_exceeded(_, __: AgentCapacityExceeded) -> JSONResponse:
        return error_response(
            status_code=503,
            code="AGENT_CAPACITY_EXHAUSTED",
            message="agent analysis capacity is temporarily exhausted",
            retryable=True,
            retry_after_seconds=1,
        )

    @app.exception_handler(httpx.HTTPStatusError)
    async def handle_model_provider_status(_, exc: httpx.HTTPStatusError) -> JSONResponse:
        provider_status = exc.response.status_code
        retryable = provider_status in {408, 429} or provider_status >= 500
        return error_response(
            status_code=503 if retryable else 424,
            code="MODEL_PROVIDER_UNAVAILABLE" if retryable else "MODEL_PROVIDER_REJECTED",
            message="model provider request failed",
            retryable=retryable,
            retry_after_seconds=provider_retry_after_seconds(exc.response),
        )

    @app.exception_handler(httpx.RequestError)
    async def handle_model_provider_network_error(_, __: httpx.RequestError) -> JSONResponse:
        return error_response(
            status_code=503,
            code="MODEL_PROVIDER_UNAVAILABLE",
            message="model provider request failed",
            retryable=True,
        )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {
            "status": "ok",
            "model": chat_model.model_name,
            "retriever": resolved.retriever_mode,
            "costEstimation": "enabled" if pricing is not None else "disabled",
            "tracing": "enabled" if active_tracing.enabled else "disabled",
            "capacityLimit": str(resolved.max_concurrent_analyses),
        }

    @app.get("/metrics", include_in_schema=False)
    async def prometheus_metrics() -> Response:
        return Response(
            content=generate_latest(metrics.registry),
            headers={"Content-Type": CONTENT_TYPE_LATEST},
        )

    @app.post("/v1/agent/analyze", response_model=AnalysisResponse)
    async def analyze(
        request: AnalysisRequest,
        x_agent_token: str | None = Header(default=None, alias="X-Agent-Token"),
    ) -> AnalysisResponse:
        if not resolved.service_token or not x_agent_token or not hmac.compare_digest(
            resolved.service_token,
            x_agent_token,
        ):
            metrics.authentication_failed()
            raise HTTPException(status_code=401, detail="invalid agent service token")

        resolved.validate_for_analysis()
        try:
            await asyncio.wait_for(
                analysis_capacity.acquire(),
                timeout=resolved.capacity_acquire_timeout_seconds,
            )
        except TimeoutError:
            metrics.analysis_capacity_rejected()
            log.warning("event=agent_analysis_capacity_rejected taskId=%s", request.task_id)
            raise AgentCapacityExceeded from None
        started = time.perf_counter()
        metrics.analysis_started()
        log.info(
            "event=agent_analysis_started taskId=%s correlationId=%s",
            request.task_id,
            request.correlation_id,
        )
        try:
            async with asyncio.timeout(resolved.analysis_timeout_seconds):
                result = await agent.run(request)
            result = result.model_copy(update={"trace_id": current_trace_id()})
            log.info(
                "event=agent_analysis_succeeded taskId=%s steps=%s toolCalls=%s durationMs=%s",
                request.task_id,
                result.steps,
                len(result.tool_trace),
                round((time.perf_counter() - started) * 1000),
            )
            metrics.requirement_results(item.status.value for item in result.requirement_results)
            metrics.analysis_finished("success", time.perf_counter() - started)
            return result
        except asyncio.CancelledError:
            metrics.analysis_finished("cancelled", time.perf_counter() - started)
            log.info(
                "event=agent_analysis_cancelled taskId=%s durationMs=%s",
                request.task_id,
                round((time.perf_counter() - started) * 1000),
            )
            raise
        except Exception as exc:
            metrics.analysis_finished(
                analysis_failure_outcome(exc),
                time.perf_counter() - started,
            )
            log.error(
                "event=agent_analysis_failed taskId=%s durationMs=%s exceptionType=%s",
                request.task_id,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
            raise
        finally:
            analysis_capacity.release()

    active_tracing.instrument_app(app)
    return app


app = create_app()
