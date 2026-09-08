# Hashing retrieval baseline

- Dataset: `retrieval-qrels-v1` (`c8423cc910a6959cae9697ea341e8cc489b7c37d80f408c5927ccc05c334932c`)
- Source revision: `9d9296c9a4ba705d13b16bd42555db72aea4cf25`
- Working tree dirty: `true`
- Retriever: `hashing-blake2b-256-v1`; Top-K: `5`

## Metrics

| Metric | Value |
| --- | ---: |
| Cases | 60 |
| Positive cases | 44 |
| No-evidence cases | 16 |
| Recall@5 | 0.8864 |
| MRR@5 | 0.8636 |
| False-positive rate | 0.0000 |
| Local retrieval p95 | 0.0452 ms |

The latency value is a local diagnostic, not a production SLA. This dataset is synthetic and is intended for retriever regression comparison, not hiring-outcome accuracy claims.

## Missed or partially recalled cases

| Case | Tags | Recall | Reciprocal rank |
| --- | --- | ---: | ---: |
| `resume-java-platform:reliable-events` | en, synonym | 0.0000 | 0.0000 |
| `resume-java-platform:monitoring-traces` | en, synonym | 0.0000 | 0.0000 |
| `resume-ml-engineering:workflow-scheduler` | en, synonym | 0.0000 | 0.0000 |
| `resume-cloud-platform:build-pipeline` | en, synonym | 0.0000 | 0.0000 |
| `resume-data-platform:data-validation` | en, synonym | 0.0000 | 0.0000 |

## False-positive cases

None.
