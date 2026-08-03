from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Settings:
    ai_endpoint: str
    ai_api_key: str
    ai_model: str
    service_token: str
    model_timeout_seconds: float = 45.0
    max_steps: int = 6
    max_tool_calls: int = 8
    max_protocol_errors: int = 2
    max_completion_tokens: int = 1_200
    max_total_tokens: int = 50_000
    max_context_chars: int = 100_000

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            ai_endpoint=os.getenv(
                "AI_ENDPOINT",
                "https://api.openai.com/v1/chat/completions",
            ),
            ai_api_key=os.getenv("AI_API_KEY", ""),
            ai_model=os.getenv("AI_MODEL", "gpt-4o-mini"),
            service_token=os.getenv("AGENT_SERVICE_TOKEN", ""),
            model_timeout_seconds=float(os.getenv("AGENT_MODEL_TIMEOUT_SECONDS", "45")),
            max_steps=int(os.getenv("AGENT_MAX_STEPS", "6")),
            max_tool_calls=int(os.getenv("AGENT_MAX_TOOL_CALLS", "8")),
            max_protocol_errors=int(os.getenv("AGENT_MAX_PROTOCOL_ERRORS", "2")),
            max_completion_tokens=int(os.getenv("AGENT_MAX_COMPLETION_TOKENS", "1200")),
            max_total_tokens=int(os.getenv("AGENT_MAX_TOTAL_TOKENS", "50000")),
            max_context_chars=int(os.getenv("AGENT_MAX_CONTEXT_CHARS", "100000")),
        )

    def validate_for_analysis(self) -> None:
        if not self.ai_endpoint.strip():
            raise RuntimeError("AI_ENDPOINT must not be blank")
        if not self.ai_api_key.strip():
            raise RuntimeError("AI_API_KEY must not be blank")
        if not self.ai_model.strip():
            raise RuntimeError("AI_MODEL must not be blank")
        if not self.service_token.strip():
            raise RuntimeError("AGENT_SERVICE_TOKEN must not be blank")
        if not 1 <= self.max_steps <= 12:
            raise RuntimeError("AGENT_MAX_STEPS must be between 1 and 12")
        if not 1 <= self.max_tool_calls <= 20:
            raise RuntimeError("AGENT_MAX_TOOL_CALLS must be between 1 and 20")
        if not 0 <= self.max_protocol_errors <= 5:
            raise RuntimeError("AGENT_MAX_PROTOCOL_ERRORS must be between 0 and 5")
        if not 1 <= self.model_timeout_seconds <= 300:
            raise RuntimeError("AGENT_MODEL_TIMEOUT_SECONDS must be between 1 and 300")
        if not 1 <= self.max_completion_tokens <= 32_768:
            raise RuntimeError("AGENT_MAX_COMPLETION_TOKENS must be between 1 and 32768")
        if not 1 <= self.max_total_tokens <= 2_000_000:
            raise RuntimeError("AGENT_MAX_TOTAL_TOKENS must be between 1 and 2000000")
        if self.max_total_tokens < self.max_completion_tokens:
            raise RuntimeError(
                "AGENT_MAX_TOTAL_TOKENS must be at least AGENT_MAX_COMPLETION_TOKENS"
            )
        if not 1_000 <= self.max_context_chars <= 2_000_000:
            raise RuntimeError("AGENT_MAX_CONTEXT_CHARS must be between 1000 and 2000000")
