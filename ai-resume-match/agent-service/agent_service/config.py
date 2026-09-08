from __future__ import annotations

import os
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from urllib.parse import urlsplit

from agent_service.costs import ModelPricing


def optional_decimal_env(name: str) -> Decimal | None:
    value = os.getenv(name, "").strip()
    if not value:
        return None
    try:
        return Decimal(value)
    except InvalidOperation:
        raise RuntimeError(f"{name} must be a decimal") from None


def boolean_env(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    normalized = value.strip().casefold()
    if normalized in {"true", "1", "yes", "on"}:
        return True
    if normalized in {"false", "0", "no", "off"}:
        return False
    raise RuntimeError(f"{name} must be true or false")


@dataclass(frozen=True, slots=True)
class Settings:
    ai_endpoint: str
    ai_api_key: str
    ai_model: str
    service_token: str
    model_timeout_seconds: float = 45.0
    analysis_timeout_seconds: float = 50.0
    max_steps: int = 6
    max_tool_calls: int = 8
    max_protocol_errors: int = 2
    max_completion_tokens: int = 1_200
    max_total_tokens: int = 50_000
    max_context_chars: int = 100_000
    max_concurrent_analyses: int = 4
    capacity_acquire_timeout_seconds: float = 0.1
    model_pricing_version: str = ""
    model_input_cost_usd_per_million_tokens: Decimal | None = None
    model_output_cost_usd_per_million_tokens: Decimal | None = None
    tracing_enabled: bool = False
    tracing_sample_probability: float = 1.0
    otel_exporter_otlp_traces_endpoint: str = "http://localhost:4318/v1/traces"
    otel_export_timeout_seconds: float = 5.0
    otel_service_name: str = "ai-resume-match-agent"
    otel_deployment_environment: str = "local"
    retriever_mode: str = "hashing"
    embedding_endpoint: str = ""
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-3-small"
    embedding_timeout_seconds: float = 15.0
    embedding_batch_size: int = 64
    dense_min_similarity: float = 0.35

    @classmethod
    def from_env(cls) -> "Settings":
        ai_api_key = os.getenv("AI_API_KEY", "")
        return cls(
            ai_endpoint=os.getenv(
                "AI_ENDPOINT",
                "https://api.openai.com/v1/chat/completions",
            ),
            ai_api_key=ai_api_key,
            ai_model=os.getenv("AI_MODEL", "gpt-4o-mini"),
            service_token=os.getenv("AGENT_SERVICE_TOKEN", ""),
            model_timeout_seconds=float(os.getenv("AGENT_MODEL_TIMEOUT_SECONDS", "45")),
            analysis_timeout_seconds=float(os.getenv("AGENT_ANALYSIS_TIMEOUT_SECONDS", "50")),
            max_steps=int(os.getenv("AGENT_MAX_STEPS", "6")),
            max_tool_calls=int(os.getenv("AGENT_MAX_TOOL_CALLS", "8")),
            max_protocol_errors=int(os.getenv("AGENT_MAX_PROTOCOL_ERRORS", "2")),
            max_completion_tokens=int(os.getenv("AGENT_MAX_COMPLETION_TOKENS", "1200")),
            max_total_tokens=int(os.getenv("AGENT_MAX_TOTAL_TOKENS", "50000")),
            max_context_chars=int(os.getenv("AGENT_MAX_CONTEXT_CHARS", "100000")),
            max_concurrent_analyses=int(
                os.getenv("AGENT_MAX_CONCURRENT_ANALYSES", "4")
            ),
            capacity_acquire_timeout_seconds=float(
                os.getenv("AGENT_CAPACITY_ACQUIRE_TIMEOUT_SECONDS", "0.1")
            ),
            model_pricing_version=os.getenv("AGENT_MODEL_PRICING_VERSION", "").strip(),
            model_input_cost_usd_per_million_tokens=optional_decimal_env(
                "AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS"
            ),
            model_output_cost_usd_per_million_tokens=optional_decimal_env(
                "AGENT_MODEL_OUTPUT_COST_USD_PER_MILLION_TOKENS"
            ),
            tracing_enabled=boolean_env("AGENT_TRACING_ENABLED"),
            tracing_sample_probability=float(os.getenv("OTEL_TRACES_SAMPLER_ARG", "1.0")),
            otel_exporter_otlp_traces_endpoint=os.getenv(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                "http://localhost:4318/v1/traces",
            ).strip(),
            otel_export_timeout_seconds=float(
                os.getenv("OTEL_EXPORTER_OTLP_TRACES_TIMEOUT_SECONDS", "5")
            ),
            otel_service_name=os.getenv(
                "OTEL_SERVICE_NAME", "ai-resume-match-agent"
            ).strip(),
            otel_deployment_environment=os.getenv(
                "OTEL_DEPLOYMENT_ENVIRONMENT", "local"
            ).strip(),
            retriever_mode=os.getenv("RETRIEVER_MODE", "hashing").strip().lower(),
            embedding_endpoint=os.getenv("EMBEDDING_ENDPOINT", "").strip(),
            embedding_api_key=os.getenv("EMBEDDING_API_KEY", "").strip() or ai_api_key,
            embedding_model=os.getenv("EMBEDDING_MODEL", "text-embedding-3-small"),
            embedding_timeout_seconds=float(
                os.getenv("EMBEDDING_TIMEOUT_SECONDS", "15")
            ),
            embedding_batch_size=int(os.getenv("EMBEDDING_BATCH_SIZE", "64")),
            dense_min_similarity=float(os.getenv("DENSE_MIN_SIMILARITY", "0.35")),
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
        if not 1 <= self.analysis_timeout_seconds <= 300:
            raise RuntimeError("AGENT_ANALYSIS_TIMEOUT_SECONDS must be between 1 and 300")
        if self.model_timeout_seconds >= self.analysis_timeout_seconds:
            raise RuntimeError(
                "AGENT_MODEL_TIMEOUT_SECONDS must be less than AGENT_ANALYSIS_TIMEOUT_SECONDS"
            )
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
        if not 1 <= self.max_concurrent_analyses <= 128:
            raise RuntimeError("AGENT_MAX_CONCURRENT_ANALYSES must be between 1 and 128")
        if not 0.001 <= self.capacity_acquire_timeout_seconds <= 5:
            raise RuntimeError(
                "AGENT_CAPACITY_ACQUIRE_TIMEOUT_SECONDS must be between 0.001 and 5"
            )
        self.model_pricing()
        self.validate_tracing()
        self.validate_retrieval()

    def model_pricing(self) -> ModelPricing | None:
        values_present = (
            bool(self.model_pricing_version.strip()),
            self.model_input_cost_usd_per_million_tokens is not None,
            self.model_output_cost_usd_per_million_tokens is not None,
        )
        if not any(values_present):
            return None
        if not all(values_present):
            raise RuntimeError(
                "AGENT_MODEL_PRICING_VERSION and both per-million-token prices must be configured together"
            )
        input_price = self.model_input_cost_usd_per_million_tokens
        output_price = self.model_output_cost_usd_per_million_tokens
        assert input_price is not None
        assert output_price is not None
        try:
            return ModelPricing(
                version=self.model_pricing_version,
                input_usd_per_million_tokens=input_price,
                output_usd_per_million_tokens=output_price,
            )
        except ValueError as exc:
            raise RuntimeError(str(exc)) from None

    def validate_retrieval(self) -> None:
        if self.retriever_mode not in {"hashing", "hybrid"}:
            raise RuntimeError("RETRIEVER_MODE must be hashing or hybrid")
        if not 1 <= self.embedding_timeout_seconds <= 120:
            raise RuntimeError("EMBEDDING_TIMEOUT_SECONDS must be between 1 and 120")
        if not 1 <= self.embedding_batch_size <= 128:
            raise RuntimeError("EMBEDDING_BATCH_SIZE must be between 1 and 128")
        if not 0 <= self.dense_min_similarity <= 1:
            raise RuntimeError("DENSE_MIN_SIMILARITY must be between 0 and 1")
        if self.retriever_mode == "hybrid":
            if self.embedding_timeout_seconds >= self.analysis_timeout_seconds:
                raise RuntimeError(
                    "EMBEDDING_TIMEOUT_SECONDS must be less than AGENT_ANALYSIS_TIMEOUT_SECONDS"
                )
            if not self.embedding_endpoint.strip():
                raise RuntimeError("EMBEDDING_ENDPOINT is required for hybrid retrieval")
            if not self.embedding_api_key.strip():
                raise RuntimeError("EMBEDDING_API_KEY is required for hybrid retrieval")
            if not self.embedding_model.strip():
                raise RuntimeError("EMBEDDING_MODEL is required for hybrid retrieval")

    def validate_tracing(self) -> None:
        if not 0 <= self.tracing_sample_probability <= 1:
            raise RuntimeError("OTEL_TRACES_SAMPLER_ARG must be between 0 and 1")
        if not 0.1 <= self.otel_export_timeout_seconds <= 30:
            raise RuntimeError(
                "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT_SECONDS must be between 0.1 and 30"
            )
        for name, value in (
            ("OTEL_SERVICE_NAME", self.otel_service_name),
            ("OTEL_DEPLOYMENT_ENVIRONMENT", self.otel_deployment_environment),
        ):
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", value):
                raise RuntimeError(f"{name} must contain 1 to 80 safe characters")
        endpoint = urlsplit(self.otel_exporter_otlp_traces_endpoint)
        try:
            endpoint.port
        except ValueError:
            raise RuntimeError(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT must contain a valid port"
            ) from None
        if (
            endpoint.scheme not in {"http", "https"}
            or not endpoint.hostname
            or endpoint.username is not None
            or endpoint.password is not None
            or endpoint.query
            or endpoint.fragment
        ):
            raise RuntimeError(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT must be an http(s) URL without credentials, query, or fragment"
            )
