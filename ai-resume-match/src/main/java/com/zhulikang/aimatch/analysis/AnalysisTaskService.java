package com.zhulikang.aimatch.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

@Service
public class AnalysisTaskService {
    static final int MAX_FAILURE_MESSAGE_LENGTH = 255;

    private static final String GENERIC_FAILURE_MESSAGE = "Analysis failed";
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");

    private final AnalysisTaskRepository taskRepository;
    private final MatchReportRepository reportRepository;
    private final Duration runningTimeout;
    private final Duration retryDelay;

    @Autowired
    public AnalysisTaskService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        @Value("${analysis.running-timeout:15m}") Duration runningTimeout,
        @Value("${analysis.retry.delay:1m}") Duration retryDelay
    ) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.runningTimeout = runningTimeout;
        this.retryDelay = retryDelay;
    }

    AnalysisTaskService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        Duration runningTimeout
    ) {
        this(taskRepository, reportRepository, runningTimeout, Duration.ofMinutes(1));
    }

    @Transactional
    public OptionalInt tryStart(Long taskId, boolean redelivered) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskRepository.markRunningIfPendingOrStale(
            taskId,
            AnalysisTask.Status.RUNNING,
            AnalysisTask.Status.PENDING,
            now.minus(runningTimeout),
            now
        );
        if (updated != 1) {
            return OptionalInt.empty();
        }

        AnalysisTask claimedTask = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalStateException("Claimed analysis task not found"));
        return OptionalInt.of(claimedTask.getAttemptCount());
    }

    @Transactional
    public boolean completeSuccess(MatchReport report, int expectedAttempt) {
        int updated = taskRepository.markSuccess(
            report.getTaskId(),
            AnalysisTask.Status.SUCCESS,
            AnalysisTask.Status.RUNNING,
            expectedAttempt,
            LocalDateTime.now()
        );
        if (updated != 1) {
            return false;
        }
        reportRepository.save(report);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(Long taskId, int expectedAttempt) {
        return markFinalFailure(taskId, expectedAttempt, AnalysisFailureCode.UNEXPECTED_ERROR);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetryableFailure(
        Long taskId,
        int expectedAttempt,
        AnalysisFailureCode failureCode
    ) {
        AnalysisTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null
            || task.getStatus() != AnalysisTask.Status.RUNNING
            || task.getAttemptCount() != expectedAttempt) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean attemptsExhausted = task.getAttemptCount() >= task.getMaxAttempts();
        int updated = taskRepository.markFailure(
            taskId,
            attemptsExhausted ? AnalysisTask.Status.FAILED_FINAL : AnalysisTask.Status.FAILED_RETRYABLE,
            AnalysisTask.Status.RUNNING,
            expectedAttempt,
            failureCode,
            userMessageFor(failureCode),
            attemptsExhausted ? null : now.plus(retryDelay),
            now
        );
        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFinalFailure(
        Long taskId,
        int expectedAttempt,
        AnalysisFailureCode failureCode
    ) {
        int updated = taskRepository.markFailure(
            taskId,
            AnalysisTask.Status.FAILED_FINAL,
            AnalysisTask.Status.RUNNING,
            expectedAttempt,
            failureCode,
            userMessageFor(failureCode),
            null,
            LocalDateTime.now()
        );
        return updated == 1;
    }

    @Transactional
    public boolean markDeliveryFailed(Long taskId, LocalDateTime now) {
        int updated = taskRepository.markDeliveryFailedIfPending(
            taskId,
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.FAILED_RETRYABLE,
            AnalysisFailureCode.DELIVERY_FAILED,
            userMessageFor(AnalysisFailureCode.DELIVERY_FAILED),
            now
        );
        return updated == 1;
    }

    static String sanitizeFailureMessage(String message) {
        String candidate = message == null ? GENERIC_FAILURE_MESSAGE : message;
        String cleaned = REPEATED_WHITESPACE.matcher(
            CONTROL_CHARACTERS.matcher(candidate).replaceAll(" ")
        ).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            cleaned = GENERIC_FAILURE_MESSAGE;
        }
        if (cleaned.length() <= MAX_FAILURE_MESSAGE_LENGTH) {
            return cleaned;
        }

        int end = MAX_FAILURE_MESSAGE_LENGTH;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))) {
            end--;
        }
        return cleaned.substring(0, end).stripTrailing();
    }

    private static String userMessageFor(AnalysisFailureCode failureCode) {
        String message = switch (Objects.requireNonNull(failureCode, "failureCode")) {
            case AI_UNAVAILABLE -> "Analysis service is temporarily unavailable";
            case DELIVERY_FAILED -> "Analysis task could not be delivered";
            case REPORT_PARSE_FAILED -> "Analysis result could not be processed";
            case SOURCE_DATA_MISSING -> "Analysis source data is missing";
            case UNEXPECTED_ERROR -> GENERIC_FAILURE_MESSAGE;
        };
        return sanitizeFailureMessage(message);
    }
}
