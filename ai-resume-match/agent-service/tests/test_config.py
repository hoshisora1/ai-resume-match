from dataclasses import replace
from decimal import Decimal

import pytest

from agent_service.config import Settings


def settings() -> Settings:
    return Settings(
        ai_endpoint="https://model.test/v1/chat/completions",
        ai_api_key="test-key",
        ai_model="test-model",
        service_token="service-token",
    )


def test_reads_model_resource_budgets_from_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_ANALYSIS_TIMEOUT_SECONDS", "55")
    monkeypatch.setenv("AGENT_MAX_COMPLETION_TOKENS", "2048")
    monkeypatch.setenv("AGENT_MAX_TOTAL_TOKENS", "64000")
    monkeypatch.setenv("AGENT_MAX_CONTEXT_CHARS", "180000")
    monkeypatch.setenv("AGENT_MAX_CONCURRENT_ANALYSES", "7")
    monkeypatch.setenv("AGENT_CAPACITY_ACQUIRE_TIMEOUT_SECONDS", "0.25")

    resolved = Settings.from_env()

    assert resolved.analysis_timeout_seconds == 55
    assert resolved.max_completion_tokens == 2_048
    assert resolved.max_total_tokens == 64_000
    assert resolved.max_context_chars == 180_000
    assert resolved.max_concurrent_analyses == 7
    assert resolved.capacity_acquire_timeout_seconds == 0.25


def test_reads_complete_versioned_model_pricing(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_MODEL_PRICING_VERSION", "provider-price-2026-08-01")
    monkeypatch.setenv("AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS", "0.15")
    monkeypatch.setenv("AGENT_MODEL_OUTPUT_COST_USD_PER_MILLION_TOKENS", "0.60")

    pricing = Settings.from_env().model_pricing()

    assert pricing is not None
    assert pricing.version == "provider-price-2026-08-01"
    assert pricing.input_usd_per_million_tokens == Decimal("0.15")
    assert pricing.output_usd_per_million_tokens == Decimal("0.60")


def test_rejects_invalid_decimal_pricing_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS", "not-a-price")

    with pytest.raises(RuntimeError, match="must be a decimal"):
        Settings.from_env()


def test_reads_hybrid_retrieval_settings_and_reuses_ai_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_API_KEY", "shared-key")
    monkeypatch.setenv("RETRIEVER_MODE", "HYBRID")
    monkeypatch.setenv("EMBEDDING_ENDPOINT", "https://model.test/v1/embeddings")
    monkeypatch.setenv("EMBEDDING_MODEL", "multilingual-embedding")
    monkeypatch.setenv("DENSE_MIN_SIMILARITY", "0.42")

    resolved = Settings.from_env()

    assert resolved.retriever_mode == "hybrid"
    assert resolved.embedding_api_key == "shared-key"
    assert resolved.embedding_model == "multilingual-embedding"
    assert resolved.dense_min_similarity == 0.42
    resolved.validate_retrieval()


@pytest.mark.parametrize(
    ("updates", "message"),
    [
        ({"max_completion_tokens": 0}, "AGENT_MAX_COMPLETION_TOKENS"),
        ({"max_completion_tokens": 32_769}, "AGENT_MAX_COMPLETION_TOKENS"),
        ({"max_total_tokens": 0}, "AGENT_MAX_TOTAL_TOKENS"),
        ({"max_total_tokens": 2_000_001}, "AGENT_MAX_TOTAL_TOKENS"),
        ({"max_context_chars": 999}, "AGENT_MAX_CONTEXT_CHARS"),
        ({"max_context_chars": 2_000_001}, "AGENT_MAX_CONTEXT_CHARS"),
        ({"max_concurrent_analyses": 0}, "AGENT_MAX_CONCURRENT_ANALYSES"),
        ({"max_concurrent_analyses": 129}, "AGENT_MAX_CONCURRENT_ANALYSES"),
        (
            {"capacity_acquire_timeout_seconds": 0},
            "AGENT_CAPACITY_ACQUIRE_TIMEOUT_SECONDS",
        ),
        (
            {"capacity_acquire_timeout_seconds": 5.1},
            "AGENT_CAPACITY_ACQUIRE_TIMEOUT_SECONDS",
        ),
        (
            {"max_completion_tokens": 2_000, "max_total_tokens": 1_999},
            "must be at least",
        ),
        ({"analysis_timeout_seconds": 0}, "AGENT_ANALYSIS_TIMEOUT_SECONDS"),
        (
            {"model_timeout_seconds": 50, "analysis_timeout_seconds": 50},
            "must be less than",
        ),
    ],
)
def test_rejects_invalid_model_resource_budgets(
    updates: dict[str, object],
    message: str,
) -> None:
    invalid = replace(settings(), **updates)

    with pytest.raises(RuntimeError, match=message):
        invalid.validate_for_analysis()


def test_accepts_default_model_resource_budgets() -> None:
    settings().validate_for_analysis()


@pytest.mark.parametrize(
    "updates",
    [
        {"model_pricing_version": "v1"},
        {
            "model_pricing_version": "v1",
            "model_input_cost_usd_per_million_tokens": Decimal("1"),
        },
        {
            "model_pricing_version": "v1",
            "model_input_cost_usd_per_million_tokens": Decimal("-1"),
            "model_output_cost_usd_per_million_tokens": Decimal("2"),
        },
    ],
)
def test_rejects_incomplete_or_invalid_model_pricing(updates: dict[str, object]) -> None:
    invalid = replace(settings(), **updates)

    with pytest.raises(RuntimeError, match="AGENT_MODEL"):
        invalid.validate_for_analysis()


@pytest.mark.parametrize(
    ("updates", "message"),
    [
        ({"retriever_mode": "dense"}, "RETRIEVER_MODE"),
        ({"embedding_timeout_seconds": 0}, "EMBEDDING_TIMEOUT_SECONDS"),
        ({"embedding_batch_size": 129}, "EMBEDDING_BATCH_SIZE"),
        ({"dense_min_similarity": 1.1}, "DENSE_MIN_SIMILARITY"),
        (
            {
                "retriever_mode": "hybrid",
                "embedding_endpoint": "",
                "embedding_api_key": "key",
            },
            "EMBEDDING_ENDPOINT",
        ),
        (
            {
                "retriever_mode": "hybrid",
                "embedding_endpoint": "https://model.test/v1/embeddings",
                "embedding_api_key": "",
            },
            "EMBEDDING_API_KEY",
        ),
    ],
)
def test_rejects_invalid_retrieval_settings(
    updates: dict[str, object],
    message: str,
) -> None:
    invalid = replace(settings(), **updates)

    with pytest.raises(RuntimeError, match=message):
        invalid.validate_retrieval()
