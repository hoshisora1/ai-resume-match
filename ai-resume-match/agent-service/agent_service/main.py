from __future__ import annotations

import hmac
import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse

from agent_service.agent import AgentProtocolError, BoundedToolAgent
from agent_service.config import Settings
from agent_service.llm import ChatModel, ModelResponseError, OpenAICompatibleChatModel
from agent_service.models import AnalysisRequest, AnalysisResponse
from agent_service.tools import ToolRegistry


log = logging.getLogger("agent_service")


def create_app(settings: Settings | None = None, model: ChatModel | None = None) -> FastAPI:
    resolved = settings or Settings.from_env()
    chat_model = model or OpenAICompatibleChatModel(
        endpoint=resolved.ai_endpoint,
        api_key=resolved.ai_api_key,
        model=resolved.ai_model,
        timeout_seconds=resolved.model_timeout_seconds,
        max_completion_tokens=resolved.max_completion_tokens,
    )
    agent = BoundedToolAgent(
        model=chat_model,
        registry=ToolRegistry(),
        max_steps=resolved.max_steps,
        max_tool_calls=resolved.max_tool_calls,
        max_protocol_errors=resolved.max_protocol_errors,
        max_total_tokens=resolved.max_total_tokens,
        max_context_chars=resolved.max_context_chars,
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

    app = FastAPI(
        title="AI Resume Match Agent Service",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )

    @app.exception_handler(AgentProtocolError)
    async def handle_protocol_error(_, exc: AgentProtocolError) -> JSONResponse:
        return JSONResponse(status_code=502, content={"code": "AGENT_PROTOCOL_ERROR", "message": str(exc)})

    @app.exception_handler(ModelResponseError)
    async def handle_model_response_error(_, exc: ModelResponseError) -> JSONResponse:
        return JSONResponse(status_code=502, content={"code": "MODEL_RESPONSE_ERROR", "message": str(exc)})

    @app.exception_handler(httpx.HTTPStatusError)
    async def handle_model_provider_status(_, exc: httpx.HTTPStatusError) -> JSONResponse:
        provider_status = exc.response.status_code
        retryable = provider_status in {408, 429} or provider_status >= 500
        return JSONResponse(
            status_code=502 if retryable else 424,
            content={
                "code": "MODEL_PROVIDER_UNAVAILABLE" if retryable else "MODEL_PROVIDER_REJECTED",
                "message": "model provider request failed",
            },
        )

    @app.exception_handler(httpx.RequestError)
    async def handle_model_provider_network_error(_, __: httpx.RequestError) -> JSONResponse:
        return JSONResponse(
            status_code=502,
            content={
                "code": "MODEL_PROVIDER_UNAVAILABLE",
                "message": "model provider request failed",
            },
        )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok", "model": chat_model.model_name}

    @app.post("/v1/agent/analyze", response_model=AnalysisResponse)
    async def analyze(
        request: AnalysisRequest,
        x_agent_token: str | None = Header(default=None, alias="X-Agent-Token"),
    ) -> AnalysisResponse:
        if not resolved.service_token or not x_agent_token or not hmac.compare_digest(
            resolved.service_token,
            x_agent_token,
        ):
            raise HTTPException(status_code=401, detail="invalid agent service token")

        resolved.validate_for_analysis()
        started = time.perf_counter()
        log.info(
            "event=agent_analysis_started taskId=%s correlationId=%s",
            request.task_id,
            request.correlation_id,
        )
        try:
            result = await agent.run(request)
            log.info(
                "event=agent_analysis_succeeded taskId=%s steps=%s toolCalls=%s durationMs=%s",
                request.task_id,
                result.steps,
                len(result.tool_trace),
                round((time.perf_counter() - started) * 1000),
            )
            return result
        except Exception as exc:
            log.error(
                "event=agent_analysis_failed taskId=%s durationMs=%s exceptionType=%s",
                request.task_id,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
            raise

    return app


app = create_app()
