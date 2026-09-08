from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import TypeAlias

from agent_service.retrieval_evaluation import load_retrieval_dataset


SpanSpec: TypeAlias = tuple[str, str]
CaseSpec: TypeAlias = tuple[str, str, list[str], list[str]]
DocumentSpec: TypeAlias = tuple[str, list[SpanSpec], list[CaseSpec]]


DOCUMENT_SPECS: list[DocumentSpec] = [
    (
        "resume-java-platform",
        [
            (
                "architecture",
                "Built Java 21 and Spring Boot services with REST APIs and MySQL transactions.",
            ),
            (
                "messaging",
                "Implemented RabbitMQ transactional outbox, publisher confirms, idempotency, and retry handling.",
            ),
            (
                "observability",
                "Added Micrometer metrics, correlation IDs, and Testcontainers integration tests.",
            ),
            ("delivery", "Deployed the service with Docker Compose for local demonstrations."),
        ],
        [
            ("java-spring", "Java Spring Boot", ["architecture"], ["en", "exact"]),
            ("rabbit-outbox", "RabbitMQ outbox idempotency", ["messaging"], ["en", "exact"]),
            (
                "reliable-events",
                "reliable event delivery",
                ["messaging"],
                ["en", "synonym"],
            ),
            (
                "monitoring-traces",
                "monitoring and distributed traces",
                ["observability"],
                ["en", "synonym"],
            ),
            ("no-mainframe", "COBOL mainframe zOS", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-python-agent",
        [
            (
                "runtime",
                "Created a Python FastAPI bounded Tool Calling Agent with three allowlisted tools and strict Pydantic schemas.",
            ),
            (
                "grounding",
                "Required every positive claim to cite evidence IDs returned by resume retrieval.",
            ),
            (
                "evaluation",
                "Added pytest regression cases for prompt injection, forged citations, empty evidence, and token budgets.",
            ),
            (
                "usage",
                "Captured provider token usage, model name, step count, and sanitized tool traces.",
            ),
        ],
        [
            ("fastapi-tools", "FastAPI tool calling", ["runtime"], ["en", "exact"]),
            ("claim-citations", "claim evidence citations", ["grounding"], ["en", "exact"]),
            (
                "adversarial-eval",
                "adversarial prompt safety evaluation",
                ["evaluation"],
                ["en", "synonym"],
            ),
            ("token-cost", "token usage cost tracking", ["usage"], ["en", "partial"]),
            ("no-robotics", "ROS robotics SLAM", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-react-product",
        [
            (
                "frontend",
                "Developed React and TypeScript pages with responsive layouts and reusable components.",
            ),
            (
                "server-state",
                "Used TanStack Query for server state, cancellation, retry backoff, and cache invalidation.",
            ),
            (
                "accessibility",
                "Implemented keyboard navigation, focus states, semantic labels, and screen reader announcements.",
            ),
            (
                "security",
                "Rendered sanitized Markdown under a restrictive Content Security Policy to reduce XSS risk.",
            ),
        ],
        [
            ("react-ts", "React TypeScript responsive UI", ["frontend"], ["en", "exact"]),
            (
                "request-cache",
                "server state request caching",
                ["server-state"],
                ["en", "exact"],
            ),
            (
                "a11y-keyboard",
                "accessible keyboard interaction",
                ["accessibility"],
                ["en", "synonym"],
            ),
            ("safe-markdown", "XSS safe Markdown CSP", ["security"], ["en", "exact"]),
            ("no-fortran", "Fortran numerical solver", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-ml-engineering",
        [
            (
                "deep-learning",
                "Fine-tuned a PyTorch transformer model and tracked validation loss across experiments.",
            ),
            (
                "classical-ml",
                "Built scikit-learn classification baselines with cross-validation and feature pipelines.",
            ),
            (
                "orchestration",
                "Scheduled batch inference and data quality checks with Apache Airflow.",
            ),
            (
                "serving",
                "Served versioned models behind a Python API and monitored prediction latency.",
            ),
        ],
        [
            ("pytorch-transformer", "PyTorch transformer", ["deep-learning"], ["en", "exact"]),
            (
                "classification-baseline",
                "classification baseline cross validation",
                ["classical-ml"],
                ["en", "exact"],
            ),
            ("workflow-scheduler", "ML workflow scheduler", ["orchestration"], ["en", "synonym"]),
            ("model-serving", "model serving latency", ["serving"], ["en", "exact"]),
            ("no-vision", "OpenCV image segmentation", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-cloud-platform",
        [
            (
                "containers",
                "Packaged services as non-root Docker images and coordinated dependencies with Compose.",
            ),
            (
                "kubernetes",
                "Deployed workloads to Kubernetes with Helm values, health probes, and resource limits.",
            ),
            (
                "ci",
                "Created GitHub Actions pipelines for tests, dependency caching, images, and release artifacts.",
            ),
            (
                "telemetry",
                "Connected OpenTelemetry traces, Prometheus metrics, and Grafana dashboards.",
            ),
        ],
        [
            ("docker-compose", "Docker Compose containers", ["containers"], ["en", "exact"]),
            ("k8s-helm", "Kubernetes Helm deployment", ["kubernetes"], ["en", "exact"]),
            ("build-pipeline", "continuous integration pipeline", ["ci"], ["en", "synonym"]),
            (
                "observability-stack",
                "distributed tracing Prometheus Grafana",
                ["telemetry"],
                ["en", "partial"],
            ),
            ("no-design", "Photoshop illustration", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-data-platform",
        [
            (
                "warehouse",
                "Modeled analytics tables in SQL and dbt with tested incremental transformations.",
            ),
            (
                "streaming",
                "Processed Kafka events with Spark Structured Streaming and checkpoint recovery.",
            ),
            (
                "quality",
                "Defined freshness, schema, and null-rate data quality checks with alerting.",
            ),
            (
                "experimentation",
                "Analyzed product funnels and A/B experiments with confidence intervals.",
            ),
        ],
        [
            ("dbt-sql", "SQL dbt transformations", ["warehouse"], ["en", "exact"]),
            ("kafka-spark", "Kafka Spark streaming", ["streaming"], ["en", "exact"]),
            ("data-validation", "dataset validation alerts", ["quality"], ["en", "synonym"]),
            ("ab-testing", "A/B experiment confidence interval", ["experimentation"], ["en", "exact"]),
            ("no-mobile", "SwiftUI iOS application", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-zh-reliability",
        [
            ("state-machine", "设计任务状态机，通过条件更新完成任务认领和状态迁移。"),
            ("outbox", "实现事务型 Outbox、发布确认、失败重试与幂等消费。"),
            ("cache", "使用 Redis 构建旁路缓存，缓存故障时回源 MySQL。"),
            ("observability", "接入关联 ID、Micrometer 指标和结构化日志。"),
        ],
        [
            ("state-claim", "任务状态机 条件认领", ["state-machine"], ["zh", "exact"]),
            ("outbox-retry", "事务消息 幂等 重试", ["outbox"], ["zh", "partial"]),
            ("cache-fallback", "缓存故障 数据库回源", ["cache"], ["zh", "exact"]),
            ("trace-metrics", "链路追踪 监控指标", ["observability"], ["zh", "synonym"]),
            ("no-game", "Unity 游戏引擎", [], ["zh", "no-evidence"]),
        ],
    ),
    (
        "resume-zh-rag",
        [
            ("chunking", "实现文档清洗、分段切分和来源位置记录。"),
            ("dense", "使用多语种向量模型完成语义检索，并缓存文档向量。"),
            ("hybrid", "融合 BM25 与向量召回，并使用重排模型优化 Top-K。"),
            ("evaluation", "建立检索评测集，计算 Recall、MRR 和无关查询误召回率。"),
        ],
        [
            ("document-chunk", "文档切分 来源位置", ["chunking"], ["zh", "exact"]),
            ("semantic-search", "语义搜索 多语言 embedding", ["dense"], ["mixed", "synonym"]),
            ("hybrid-rerank", "BM25 向量混合检索 重排", ["hybrid"], ["mixed", "exact"]),
            ("retrieval-metrics", "召回率 MRR 评测", ["evaluation"], ["mixed", "partial"]),
            ("no-accounting", "财务审计 税务申报", [], ["zh", "no-evidence"]),
        ],
    ),
    (
        "resume-mixed-fullstack",
        [
            ("backend", "负责 Java backend，使用 Spring Boot、MySQL 和 RabbitMQ。"),
            ("agent", "编写 Python Agent 服务，实现 tool calling 与 evidence grounding。"),
            ("frontend", "交付 React 前端，支持异步轮询、失败重试和报告展示。"),
            ("quality", "CI 中运行 JUnit、pytest、Vitest and Playwright tests。"),
        ],
        [
            ("java-mq", "Java RabbitMQ 后端", ["backend"], ["mixed", "exact"]),
            ("agent-grounding", "Agent 工具调用 证据约束", ["agent"], ["mixed", "synonym"]),
            ("react-poll", "React polling retry", ["frontend"], ["mixed", "partial"]),
            ("test-stack", "pytest Playwright CI", ["quality"], ["mixed", "exact"]),
            ("no-blockchain", "Solidity smart contract", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-negative-evidence",
        [
            ("kubernetes", "该项目未使用 Kubernetes，仅通过 Docker Compose 在本地运行。"),
            ("traffic", "没有真实生产流量，所有性能数据均来自本机测试。"),
            ("vector-db", "未接入向量数据库，当前检索使用本地 hashing 方法。"),
            ("management", "没有团队管理经历，项目由个人独立完成。"),
        ],
        [
            ("k8s-negative", "Kubernetes 使用经验", ["kubernetes"], ["zh", "negation"]),
            ("traffic-negative", "生产流量 性能", ["traffic"], ["zh", "negation"]),
            ("vector-negative", "向量数据库", ["vector-db"], ["zh", "negation"]),
            ("leadership-negative", "团队管理", ["management"], ["zh", "negation"]),
            ("no-sap", "SAP ABAP", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-long-noisy",
        [
            (
                "long-tail",
                " ".join(f"neutralword{index}" for index in range(180))
                + " Implemented Temporal workflow orchestration and durable timers.",
            ),
            (
                "long-head",
                "Built Elasticsearch search relevance tests. "
                + " ".join(f"fillerword{index}" for index in range(180)),
            ),
            ("compact", "Maintained concise ADR documents for architecture decisions."),
        ],
        [
            ("tail-temporal", "Temporal durable workflow", ["long-tail"], ["en", "long"]),
            (
                "head-elastic",
                "Elasticsearch relevance",
                ["long-head"],
                ["en", "long"],
            ),
            ("adr", "architecture decision records", ["compact"], ["en", "synonym"]),
            (
                "both-long",
                "workflow search relevance",
                ["long-tail", "long-head"],
                ["en", "multi-span"],
            ),
            ("no-quantum", "quantum circuit Qiskit", [], ["en", "no-evidence"]),
        ],
    ),
    (
        "resume-general-operations",
        [
            ("coordination", "Coordinated weekly schedules and documented meeting decisions."),
            ("support", "Answered customer questions and maintained a searchable help center."),
            ("reporting", "Prepared monthly spreadsheet reports for inventory and purchasing."),
        ],
        [
            ("no-rust", "Rust Tokio async runtime", [], ["en", "no-evidence"]),
            ("no-llm", "large language model fine tuning", [], ["en", "no-evidence"]),
            ("no-rabbit", "RabbitMQ transactional outbox", [], ["en", "no-evidence"]),
            ("no-k8s", "Kubernetes Helm", [], ["en", "no-evidence"]),
            ("no-rag", "向量检索 重排", [], ["zh", "no-evidence"]),
        ],
    ),
]


def build_dataset() -> dict[str, object]:
    documents: list[dict[str, object]] = []
    cases: list[dict[str, object]] = []

    for document_id, span_specs, case_specs in DOCUMENT_SPECS:
        text_parts: list[str] = []
        spans: list[dict[str, object]] = []
        cursor = 0
        span_ids: dict[str, str] = {}
        for index, (span_name, span_text) in enumerate(span_specs):
            if index:
                text_parts.append("\n\n")
                cursor += 2
            span_id = f"{document_id}:{span_name}"
            span_ids[span_name] = span_id
            start = cursor
            text_parts.append(span_text)
            cursor += len(span_text)
            spans.append({"spanId": span_id, "start": start, "end": cursor})

        documents.append(
            {
                "documentId": document_id,
                "text": "".join(text_parts),
                "spans": spans,
            }
        )
        for case_name, query, relevant_names, tags in case_specs:
            cases.append(
                {
                    "id": f"{document_id}:{case_name}",
                    "documentId": document_id,
                    "query": query,
                    "relevantSpanIds": [span_ids[name] for name in relevant_names],
                    "noRelevantEvidence": not relevant_names,
                    "tags": tags,
                }
            )

    return {
        "datasetVersion": "retrieval-qrels-v1",
        "documents": documents,
        "cases": cases,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the synthetic retrieval qrels dataset")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).with_name("retrieval_dataset_v1.json"),
    )
    args = parser.parse_args()
    rendered = json.dumps(build_dataset(), ensure_ascii=False, indent=2) + "\n"
    args.output.write_text(rendered, encoding="utf-8", newline="\n")
    dataset, digest = load_retrieval_dataset(args.output)
    print(
        f"wrote {len(dataset.documents)} documents and {len(dataset.cases)} cases "
        f"to {args.output} (sha256={digest})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
