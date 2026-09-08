package com.zhulikang.aimatch.observability;

import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class AnalysisMetrics {
    private final MeterRegistry meterRegistry;

    public AnalysisMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void taskCreated() {
        Counter.builder("analysis.tasks.created").register(meterRegistry).increment();
    }

    public void taskSucceeded(Timer.Sample sample) {
        Counter.builder("analysis.tasks.succeeded").register(meterRegistry).increment();
        stopWorker(sample, "success", "none");
    }

    public void taskFailed(AnalysisFailureCode failureCode, Timer.Sample sample) {
        String code = failureCode == null ? "UNKNOWN" : failureCode.name();
        Counter.builder("analysis.tasks.failed")
            .tag("failureCode", code)
            .register(meterRegistry)
            .increment();
        stopWorker(sample, "failure", code);
    }

    public void taskLeaseLost(Timer.Sample sample) {
        Counter.builder("analysis.tasks.stale_leases")
            .register(meterRegistry)
            .increment();
        stopWorker(sample, "stale_lease", "none");
    }

    public void outboxPublished() {
        Counter.builder("analysis.outbox.events")
            .tag("outcome", "published")
            .register(meterRegistry)
            .increment();
    }

    public void outboxFailed() {
        Counter.builder("analysis.outbox.events")
            .tag("outcome", "failed")
            .register(meterRegistry)
            .increment();
    }

    public void outboxDead() {
        Counter.builder("analysis.outbox.events")
            .tag("outcome", "dead")
            .register(meterRegistry)
            .increment();
    }

    public void retentionDeleted(String resource, int count) {
        if (count < 1) {
            return;
        }
        Counter.builder("analysis.retention.deleted")
            .tag("resource", resource)
            .register(meterRegistry)
            .increment(count);
    }

    public void cacheRequest(String result) {
        Counter.builder("report.cache.requests")
            .tag("result", result)
            .register(meterRegistry)
            .increment();
    }

    public void cacheWrite(String outcome) {
        Counter.builder("report.cache.writes")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void cacheEviction(String outcome) {
        Counter.builder("report.cache.evictions")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void modelInputRedacted(String type, int count) {
        if (count < 1) {
            return;
        }
        Counter.builder("analysis.model.input.redactions")
            .tag("type", type)
            .register(meterRegistry)
            .increment(count);
    }

    public Timer.Sample startAiCall() {
        return Timer.start(meterRegistry);
    }

    public void aiCallFinished(Timer.Sample sample, String outcome) {
        Counter.builder("ai.calls")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
        sample.stop(Timer.builder("ai.call.duration")
            .tag("outcome", outcome)
            .register(meterRegistry));
    }

    public Timer.Sample startAgentCall() {
        return Timer.start(meterRegistry);
    }

    public void agentCallFinished(Timer.Sample sample, String outcome) {
        Counter.builder("agent.calls")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
        sample.stop(Timer.builder("agent.call.duration")
            .tag("outcome", outcome)
            .register(meterRegistry));
    }

    private void stopWorker(Timer.Sample sample, String outcome, String failureCode) {
        if (sample == null) {
            return;
        }
        sample.stop(Timer.builder("analysis.worker.duration")
            .tag("outcome", outcome)
            .tag("failureCode", failureCode)
            .register(meterRegistry));
    }
}
