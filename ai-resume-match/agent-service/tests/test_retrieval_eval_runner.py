import pytest

from evals.run_retrieval_eval import compare_with_baseline


def result(*, digest: str, recall: float, mrr: float, false_positive: float) -> dict:
    return {
        "datasetSha256": digest,
        "retriever": {"version": "test-v1", "topK": 5},
        "metrics": {
            "recallAt5": recall,
            "mrrAt5": mrr,
            "falsePositiveRate": false_positive,
        },
    }


def test_compares_retrievers_only_on_identical_dataset_and_top_k() -> None:
    baseline = result(digest="a" * 64, recall=0.8, mrr=0.7, false_positive=0.1)
    current = result(digest="a" * 64, recall=0.9, mrr=0.75, false_positive=0.05)
    current["retriever"]["version"] = "hybrid-v1"

    comparison = compare_with_baseline(current, baseline)

    assert comparison["baselineRetriever"] == "test-v1"
    assert comparison["recallLift"] == 0.1
    assert comparison["mrrLift"] == 0.05
    assert comparison["falsePositiveRateIncrease"] == -0.05


def test_rejects_baseline_from_different_dataset_or_top_k() -> None:
    current = result(digest="a" * 64, recall=0.9, mrr=0.8, false_positive=0)
    wrong_dataset = result(digest="b" * 64, recall=0.8, mrr=0.7, false_positive=0)
    wrong_top_k = result(digest="a" * 64, recall=0.8, mrr=0.7, false_positive=0)
    wrong_top_k["retriever"]["topK"] = 10

    with pytest.raises(ValueError, match="dataset hash"):
        compare_with_baseline(current, wrong_dataset)
    with pytest.raises(ValueError, match="Top-K"):
        compare_with_baseline(current, wrong_top_k)
