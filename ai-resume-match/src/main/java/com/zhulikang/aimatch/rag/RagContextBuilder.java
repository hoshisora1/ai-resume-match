package com.zhulikang.aimatch.rag;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagContextBuilder {
    public String build(List<String> retrievedResumeChunks, String jdText, List<String> tags) {
        return """
            你是资深 Java 后端面试官，请根据简历内容和岗位 JD 生成匹配报告。

            输出格式：
            1. 匹配分数：0-100 的整数
            2. 技能匹配：列出已匹配技能
            3. 技能差距：列出缺失或薄弱技能
            4. 项目优化建议：给出 3 条可落地建议
            5. 模拟面试题：给出 5 个后端相关问题

            JD 技能标签：%s

            召回的简历片段：
            %s

            岗位 JD：
            %s
            """.formatted(String.join(",", tags), String.join("\n---\n", retrievedResumeChunks), jdText);
    }
}
