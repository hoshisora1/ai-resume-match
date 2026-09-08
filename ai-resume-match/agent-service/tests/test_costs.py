from decimal import Decimal

import pytest
from pydantic import ValidationError

from agent_service.costs import ModelPricing
from agent_service.models import ModelUsage


def test_estimates_usd_cost_only_from_complete_provider_usage() -> None:
    pricing = ModelPricing(
        version="provider-price-2026-08-01",
        input_usd_per_million_tokens=Decimal("0.15"),
        output_usd_per_million_tokens=Decimal("0.60"),
    )

    estimate = pricing.estimate(
        ModelUsage(
            promptTokens=1_000,
            completionTokens=200,
            totalTokens=1_200,
            providerReported=True,
        )
    )

    assert estimate == Decimal("0.00027000")


def test_does_not_estimate_cost_from_partial_usage() -> None:
    pricing = ModelPricing(
        version="provider-price-2026-08-01",
        input_usd_per_million_tokens=Decimal("1"),
        output_usd_per_million_tokens=Decimal("2"),
    )

    assert pricing.estimate(ModelUsage(promptTokens=100, providerReported=False)) is None


def test_model_usage_requires_versioned_cost_and_complete_usage() -> None:
    with pytest.raises(ValidationError, match="pricing version"):
        ModelUsage(
            promptTokens=100,
            providerReported=True,
            estimatedCostUsd="0.00010000",
        )

    with pytest.raises(ValidationError, match="complete provider-reported usage"):
        ModelUsage(
            promptTokens=100,
            providerReported=False,
            estimatedCostUsd="0.00010000",
            pricingVersion="v1",
        )


@pytest.mark.parametrize(
    ("version", "input_price", "output_price"),
    [
        ("", Decimal("1"), Decimal("2")),
        ("unsafe\nversion", Decimal("1"), Decimal("2")),
        ("v1", Decimal("-1"), Decimal("2")),
        ("v1", Decimal("NaN"), Decimal("2")),
        ("v1", Decimal("1"), Decimal("100001")),
    ],
)
def test_rejects_invalid_pricing_metadata(
    version: str,
    input_price: Decimal,
    output_price: Decimal,
) -> None:
    with pytest.raises(ValueError):
        ModelPricing(
            version=version,
            input_usd_per_million_tokens=input_price,
            output_usd_per_million_tokens=output_price,
        )
