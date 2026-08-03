# AI Resume Match

[![CI](https://github.com/hoshisora1/ai-resume-match/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/hoshisora1/ai-resume-match/actions/workflows/ci.yml)

AI Resume Match is a full-stack AI application for uploading a PDF/DOCX resume, matching it against a job description, tracking an asynchronous analysis task, and reviewing a grounded Markdown report.

The implementation combines a React product UI, a Spring Boot reliability boundary, and a bounded FastAPI Tool Calling Agent. MySQL remains the source of truth; a transactional outbox and RabbitMQ drive background work; Redis is used only as a report cache.

## Portfolio highlights

- Bounded Tool Calling Agent with three allow-listed tools, evidence references, schema validation, and step/tool/token/context budgets.
- Idempotent multipart submission, transactional outbox delivery, finite retry/dead-letter handling, stale-task recovery, and attempt fencing.
- React workflow for submission, progress polling, retries, history filtering, and safe Markdown rendering.
- Layered verification with JUnit, Testcontainers, pytest, Vitest/MSW, deterministic Playwright, and a real Docker Compose browser flow.

The application source, architecture, setup instructions, API examples, and validation commands are documented in the [project README](ai-resume-match/README.md).

Key engineering evidence:

- [Architecture](ai-resume-match/docs/architecture.md)
- [Development and testing](ai-resume-match/docs/development.md)
- [Agent engineering evidence](ai-resume-match/docs/agent-engineering-evidence.md)
- [Operations runbook](ai-resume-match/docs/operations/runbook.md)

> This is an engineering portfolio project for local or controlled environments. It does not claim production traffic, measured business outcomes, or a complete identity/multi-tenant security model.
