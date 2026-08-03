from __future__ import annotations

from typing import Any, Protocol

import httpx

from agent_service.models import AssistantTurn, ModelToolCall, ModelUsage


class ChatModel(Protocol):
    @property
    def model_name(self) -> str: ...

    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
    ) -> AssistantTurn: ...


class ModelResponseError(RuntimeError):
    """The upstream model response does not satisfy the tool-calling contract."""


class OpenAICompatibleChatModel:
    def __init__(
        self,
        *,
        endpoint: str,
        api_key: str,
        model: str,
        timeout_seconds: float,
        max_completion_tokens: int = 1_200,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not 1 <= max_completion_tokens <= 32_768:
            raise ValueError("max_completion_tokens must be between 1 and 32768")
        self._endpoint = endpoint
        self._api_key = api_key
        self._model = model
        self._max_completion_tokens = max_completion_tokens
        self._timeout = httpx.Timeout(timeout_seconds, connect=min(timeout_seconds, 5.0))
        self._client = httpx.AsyncClient(timeout=self._timeout, transport=transport)

    @property
    def model_name(self) -> str:
        return self._model

    async def aclose(self) -> None:
        await self._client.aclose()

    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
    ) -> AssistantTurn:
        if not self._api_key.strip():
            raise RuntimeError("AI_API_KEY must not be blank")

        payload = {
            "model": self._model,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "temperature": 0,
            "max_completion_tokens": self._max_completion_tokens,
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        response = await self._client.post(self._endpoint, headers=headers, json=payload)
        response.raise_for_status()

        try:
            body = response.json()
            message = body["choices"][0]["message"]
            raw_tool_calls = message.get("tool_calls") or []
            tool_calls = [
                ModelToolCall(
                    call_id=item["id"],
                    name=item["function"]["name"],
                    arguments=item["function"].get("arguments") or "{}",
                )
                for item in raw_tool_calls
            ]
            content = message.get("content")
            if content is not None and not isinstance(content, str):
                raise TypeError("message content is not a string")
            raw_usage = body.get("usage")
            if raw_usage is None:
                raw_usage = {}
            if not isinstance(raw_usage, dict):
                raise TypeError("usage is not an object")
            provider_reported = all(
                name in raw_usage
                for name in ("prompt_tokens", "completion_tokens", "total_tokens")
            )
            usage = ModelUsage(
                prompt_tokens=raw_usage.get("prompt_tokens", 0),
                completion_tokens=raw_usage.get("completion_tokens", 0),
                total_tokens=raw_usage.get("total_tokens", 0),
                provider_reported=provider_reported,
            )
            return AssistantTurn(content=content, tool_calls=tool_calls, usage=usage)
        except (KeyError, IndexError, TypeError, ValueError):
            # Provider validation errors can embed input_value, including model text.
            # Suppress that context so a later traceback cannot copy it into logs.
            raise ModelResponseError("invalid OpenAI-compatible response") from None
