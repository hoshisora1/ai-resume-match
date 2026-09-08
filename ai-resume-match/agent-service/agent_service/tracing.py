from __future__ import annotations

from dataclasses import dataclass

import httpx
from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import (
    BatchSpanProcessor,
    SimpleSpanProcessor,
    SpanExporter,
)
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from opentelemetry.trace import Tracer

from agent_service.config import Settings


@dataclass(slots=True)
class AgentTracing:
    enabled: bool
    tracer_provider: TracerProvider | None
    tracer: Tracer

    @classmethod
    def create(
        cls,
        settings: Settings,
        *,
        span_exporter: SpanExporter | None = None,
    ) -> AgentTracing:
        settings.validate_tracing()
        if not settings.tracing_enabled:
            return cls(
                enabled=False,
                tracer_provider=None,
                tracer=trace.get_tracer("ai-resume-match-agent"),
            )

        provider = TracerProvider(
            resource=Resource.create(
                {
                    "service.name": settings.otel_service_name,
                    "service.version": "0.1.0",
                    "deployment.environment.name": settings.otel_deployment_environment,
                }
            ),
            sampler=ParentBased(TraceIdRatioBased(settings.tracing_sample_probability)),
        )
        if span_exporter is None:
            exporter = OTLPSpanExporter(
                endpoint=settings.otel_exporter_otlp_traces_endpoint,
                timeout=settings.otel_export_timeout_seconds,
            )
            provider.add_span_processor(BatchSpanProcessor(exporter))
        else:
            provider.add_span_processor(SimpleSpanProcessor(span_exporter))
        return cls(
            enabled=True,
            tracer_provider=provider,
            tracer=provider.get_tracer("ai-resume-match-agent", "0.1.0"),
        )

    def instrument_app(self, app: FastAPI) -> None:
        if self.tracer_provider is None:
            return
        FastAPIInstrumentor.instrument_app(
            app,
            tracer_provider=self.tracer_provider,
            excluded_urls=r"/health(?:\?.*)?$,/metrics(?:\?.*)?$",
        )

    def instrument_httpx_client(self, client: httpx.Client | httpx.AsyncClient) -> None:
        if self.tracer_provider is None:
            return
        HTTPXClientInstrumentor.instrument_client(
            client,
            tracer_provider=self.tracer_provider,
        )

    def shutdown(self) -> None:
        if self.tracer_provider is not None:
            self.tracer_provider.shutdown()


def current_trace_id() -> str | None:
    context = trace.get_current_span().get_span_context()
    if not context.is_valid:
        return None
    return format(context.trace_id, "032x")
