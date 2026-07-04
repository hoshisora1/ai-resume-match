package com.zhulikang.aimatch.analysis;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReportParser {
    private static final Pattern SCORE_PATTERN = Pattern.compile("匹配分数\\s*[:：]\\s*(\\d{1,3})");

    public int extractScore(String report) {
        Matcher matcher = SCORE_PATTERN.matcher(report == null ? "" : report);
        if (!matcher.find()) {
            throw new IllegalArgumentException("AI report does not contain 匹配分数");
        }
        int score = Integer.parseInt(matcher.group(1));
        return Math.max(0, Math.min(100, score));
    }
}
