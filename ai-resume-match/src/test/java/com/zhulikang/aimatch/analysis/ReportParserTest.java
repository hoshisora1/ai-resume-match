package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportParserTest {
    private final ReportParser reportParser = new ReportParser();

    @Test
    void extractsChineseColonScore() {
        assertThat(reportParser.extractScore("匹配分数：88\n技能匹配：Redis")).isEqualTo(88);
    }

    @Test
    void extractsAsciiColonScore() {
        assertThat(reportParser.extractScore("匹配分数: 77")).isEqualTo(77);
    }

    @Test
    void clampsScoreAboveOneHundred() {
        assertThat(reportParser.extractScore("匹配分数：150")).isEqualTo(100);
    }

    @Test
    void rejectsMissingScore() {
        assertThatThrownBy(() -> reportParser.extractScore("技能匹配：Redis"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("匹配分数");
    }
}
