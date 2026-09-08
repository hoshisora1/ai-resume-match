from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP

from agent_service.models import ModelUsage


MILLION_TOKENS = Decimal(1_000_000)
COST_QUANTUM_USD = Decimal("0.00000001")


@dataclass(frozen=True, slots=True)
class ModelPricing:
    version: str
    input_usd_per_million_tokens: Decimal
    output_usd_per_million_tokens: Decimal

    def __post_init__(self) -> None:
        version = self.version.strip()
        if not version or len(version) > 120 or any(not character.isprintable() for character in version):
            raise ValueError("AGENT_MODEL_PRICING_VERSION must be 1 to 120 safe characters")
        for name, value in (
            ("AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS", self.input_usd_per_million_tokens),
            (
                "AGENT_MODEL_OUTPUT_COST_USD_PER_MILLION_TOKENS",
                self.output_usd_per_million_tokens,
            ),
        ):
            if not value.is_finite() or value < 0 or value > Decimal("100000"):
                raise ValueError(f"{name} must be a finite decimal between 0 and 100000")
        object.__setattr__(self, "version", version)

    def estimate(self, usage: ModelUsage) -> Decimal | None:
        if not usage.provider_reported:
            return None
        cost = (
            Decimal(usage.prompt_tokens) * self.input_usd_per_million_tokens
            + Decimal(usage.completion_tokens) * self.output_usd_per_million_tokens
        ) / MILLION_TOKENS
        return cost.quantize(COST_QUANTUM_USD, rounding=ROUND_HALF_UP)
