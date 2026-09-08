package com.zhulikang.aimatch.analysis;

import java.util.Optional;

public interface ReportCache {
    Optional<MatchReportView> get(Long taskId);

    void put(MatchReportView report);

    void evict(Long taskId);
}
