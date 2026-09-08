package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AnalysisOutboxRepositoryTest {
    private static final List<AnalysisOutboxStatus> CLAIMABLE_STATUSES = List.of(
        AnalysisOutboxStatus.PENDING,
        AnalysisOutboxStatus.FAILED,
        AnalysisOutboxStatus.PROCESSING
    );

    @Autowired
    AnalysisOutboxRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void findsOldestNonTerminalCreationTimeWithoutLoadingPayloads() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 12, 0);
        AnalysisOutboxEvent oldestActive = AnalysisOutboxEvent.analysisRequested(90L);
        ReflectionTestUtils.setField(oldestActive, "createdAt", now.minusMinutes(10));
        AnalysisOutboxEvent newerActive = AnalysisOutboxEvent.analysisRequested(91L);
        ReflectionTestUtils.setField(newerActive, "createdAt", now.minusMinutes(2));
        AnalysisOutboxEvent olderDead = AnalysisOutboxEvent.analysisRequested(92L);
        ReflectionTestUtils.setField(olderDead, "createdAt", now.minusHours(1));
        olderDead.markPublishFailed("exhausted", now, now, 1);
        repository.saveAllAndFlush(List.of(oldestActive, newerActive, olderDead));

        assertThat(repository.findOldestCreatedAtByStatuses(CLAIMABLE_STATUSES))
            .contains(now.minusMinutes(10));
    }

    @Test
    void guardedClaimAllowsOnlyOnePublisherInstance() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        AnalysisOutboxEvent event = repository.saveAndFlush(
            AnalysisOutboxEvent.analysisRequested(99L)
        );

        assertThat(repository.findDueForPublishIds(
            CLAIMABLE_STATUSES,
            now,
            10,
            PageRequest.of(0, 20)
        )).containsExactly(event.getId());

        int firstClaim = repository.markProcessingIfDue(
            event.getId(),
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            "lease-a",
            now.plusSeconds(30)
        );
        int competingClaim = repository.markProcessingIfDue(
            event.getId(),
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            "lease-b",
            now.plusSeconds(30)
        );

        assertThat(firstClaim).isEqualTo(1);
        assertThat(competingClaim).isZero();
        AnalysisOutboxEvent claimed = repository.findById(event.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(AnalysisOutboxStatus.PROCESSING);
        assertThat(claimed.getNextAttemptAt()).isNull();
        assertThat(claimed.getLeaseToken()).isEqualTo("lease-a");
        assertThat(claimed.getLeaseUntil()).isEqualTo(now.plusSeconds(30));
        assertThat(repository.findByIdAndStatusAndLeaseToken(
            event.getId(),
            AnalysisOutboxStatus.PROCESSING,
            "lease-b"
        )).isEmpty();
    }

    @Test
    void expiredLeaseCanBeReclaimedAndFencesThePreviousPublisher() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        AnalysisOutboxEvent event = repository.saveAndFlush(
            AnalysisOutboxEvent.analysisRequested(103L)
        );

        assertThat(repository.markProcessingIfDue(
            event.getId(),
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            "lease-a",
            now.plusSeconds(30)
        )).isEqualTo(1);
        assertThat(repository.markProcessingIfDue(
            event.getId(),
            CLAIMABLE_STATUSES,
            now.plusSeconds(31),
            10,
            AnalysisOutboxStatus.PROCESSING,
            "lease-b",
            now.plusSeconds(61)
        )).isEqualTo(1);

        assertThat(repository.findByIdAndStatusAndLeaseToken(
            event.getId(),
            AnalysisOutboxStatus.PROCESSING,
            "lease-a"
        )).isEmpty();
        assertThat(repository.findByIdAndStatusAndLeaseToken(
            event.getId(),
            AnalysisOutboxStatus.PROCESSING,
            "lease-b"
        )).isPresent();
        AnalysisOutboxEvent reclaimed = repository.findById(event.getId()).orElseThrow();
        assertThat(reclaimed.getLeaseToken()).isEqualTo("lease-b");
        assertThat(reclaimed.getLeaseUntil()).isEqualTo(now.plusSeconds(61));
    }

    @Test
    void deadEventsStayTerminalAndExhaustedEventsAreGuardedlySwept() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        AnalysisOutboxEvent dead = AnalysisOutboxEvent.analysisRequested(100L);
        assertThat(dead.markPublishFailed("first", now.minusSeconds(2), now.minusSeconds(1), 2))
            .isFalse();
        assertThat(dead.markPublishFailed("final", now.minusSeconds(1), now, 2)).isTrue();
        dead = repository.saveAndFlush(dead);

        AnalysisOutboxEvent exhausted = AnalysisOutboxEvent.analysisRequested(101L);
        ReflectionTestUtils.setField(exhausted, "status", AnalysisOutboxStatus.FAILED);
        ReflectionTestUtils.setField(exhausted, "attemptCount", 10);
        ReflectionTestUtils.setField(exhausted, "nextAttemptAt", now.minusSeconds(1));
        exhausted = repository.saveAndFlush(exhausted);

        assertThat(repository.findExhaustedNonTerminalIds(
            CLAIMABLE_STATUSES,
            10,
            PageRequest.of(0, 20)
        )).containsExactly(exhausted.getId());
        assertThat(repository.markDeadIfExhausted(
            exhausted.getId(),
            CLAIMABLE_STATUSES,
            10,
            AnalysisOutboxStatus.DEAD,
            now
        )).isEqualTo(1);
        assertThat(repository.markDeadIfExhausted(
            exhausted.getId(),
            CLAIMABLE_STATUSES,
            10,
            AnalysisOutboxStatus.DEAD,
            now
        )).isZero();

        assertThat(repository.findDueForPublishIds(
            CLAIMABLE_STATUSES,
            now,
            10,
            PageRequest.of(0, 20)
        )).isEmpty();
        assertThat(repository.markProcessingIfDue(
            dead.getId(),
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            "dead-lease",
            now.plusSeconds(30)
        )).isZero();
        assertThat(repository.findById(exhausted.getId()).orElseThrow().getStatus())
            .isEqualTo(AnalysisOutboxStatus.DEAD);
        assertThat(repository.markProcessingIfDue(
            exhausted.getId(),
            CLAIMABLE_STATUSES,
            now,
            10,
            AnalysisOutboxStatus.PROCESSING,
            "exhausted-lease",
            now.plusSeconds(30)
        )).isZero();
    }

    @Test
    void finalFailurePersistsTruncatedErrorAndClearsRetryTime() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(102L);

        assertThat(event.markPublishFailed(
            "safe prefix\u0000\r\nforged-entry\u202E " + "x".repeat(2_000),
            now,
            now.plusSeconds(30),
            1
        )).isTrue();
        event = repository.saveAndFlush(event);

        AnalysisOutboxEvent saved = repository.findById(event.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AnalysisOutboxStatus.DEAD);
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getLastError()).hasSize(1_024);
        assertThat(saved.getLastError()).startsWith("safe prefix forged-entry ")
            .doesNotContain("\u0000", "\r", "\n", "\u202E");
        assertThat(saved.getNextAttemptAt()).isNull();
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getTerminalAt()).isEqualTo(now);
    }
}
