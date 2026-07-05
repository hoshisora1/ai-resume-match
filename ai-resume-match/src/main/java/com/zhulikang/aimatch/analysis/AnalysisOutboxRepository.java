package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisOutboxRepository extends JpaRepository<AnalysisOutboxEvent, Long> {
}
