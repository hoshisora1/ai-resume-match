package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.application.analysis.AnalysisPage;

import java.util.List;

public record AnalysisPageResponse(
    List<AnalysisListItemResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static AnalysisPageResponse from(AnalysisPage page) {
        return new AnalysisPageResponse(
            page.items().stream().map(AnalysisListItemResponse::from).toList(),
            page.page(),
            page.size(),
            page.totalElements(),
            page.totalPages()
        );
    }
}
