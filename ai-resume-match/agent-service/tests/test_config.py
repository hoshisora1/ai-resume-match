from dataclasses import replace

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
    monkeypatch.setenv("AGENT_MAX_COMPLETION_TOKENS", "2048")
    monkeypatch.setenv("AGENT_MAX_TOTAL_TOKENS", "64000")
    monkeypatch.setenv("AGENT_MAX_CONTEXT_CHARS", "180000")

    resolved = Settings.from_env()

    assert resolved.max_completion_tokens == 2_048
    assert resolved.max_total_tokens == 64_000
    assert resolved.max_context_chars == 180_000


@pytest.mark.parametrize(
    ("updates", "message"),
    [
        ({"max_completion_tokens": 0}, "AGENT_MAX_COMPLETION_TOKENS"),
        ({"max_completion_tokens": 32_769}, "AGENT_MAX_COMPLETION_TOKENS"),
        ({"max_total_tokens": 0}, "AGENT_MAX_TOTAL_TOKENS"),
        ({"max_total_tokens": 2_000_001}, "AGENT_MAX_TOTAL_TOKENS"),
        ({"max_context_chars": 999}, "AGENT_MAX_CONTEXT_CHARS"),
        ({"max_context_chars": 2_000_001}, "AGENT_MAX_CONTEXT_CHARS"),
        (
            {"max_completion_tokens": 2_000, "max_total_tokens": 1_999},
            "must be at least",
        ),
    ],
)
def test_rejects_invalid_model_resource_budgets(
    updates: dict[str, int],
    message: str,
) -> None:
    invalid = replace(settings(), **updates)

    with pytest.raises(RuntimeError, match=message):
        invalid.validate_for_analysis()


def test_accepts_default_model_resource_budgets() -> None:
    settings().validate_for_analysis()
