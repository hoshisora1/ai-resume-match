package com.zhulikang.aimatch.application.analysis;

import java.util.List;

public record AnalysisPage(
    List<AnalysisListItem> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
