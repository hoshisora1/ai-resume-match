package com.zhulikang.aimatch.application.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelInputPrivacySanitizerTest {
    private final ModelInputPrivacySanitizer sanitizer = new ModelInputPrivacySanitizer();

    @Test
    void redactsCommonContactIdentityAndProtectedAttributesBeforeModelUse() {
        AnalysisInput input = new AnalysisInput(
            7L,
            "姓名：张三\nEmail: jane.doe@example.com\n电话：13800138000\n"
                + "身份证：11010519491231002X\nAddress: 10 Example Road, Singapore\n"
                + "Gender: female\nJava 21, Spring Boot, 2022-2024",
            "Backend Engineer",
            "Contact recruiter@example.org or +65 6123 4567\n年龄：31",
            List.of("Java", "owner@example.org"),
            "correlation-7"
        );

        ModelInputPrivacySanitizer.SanitizationResult result = sanitizer.sanitize(input);

        assertThat(result.input().resumeText())
            .doesNotContain("张三", "jane.doe@example.com", "13800138000", "11010519491231002X")
            .doesNotContain("10 Example Road", "female")
            .contains("[REDACTED_NAME]", "[REDACTED_EMAIL]", "[REDACTED_PHONE]")
            .contains("[REDACTED_GOVERNMENT_ID]", "[REDACTED_ADDRESS]", "[REDACTED_PROTECTED_ATTRIBUTE]")
            .contains("Java 21", "2022-2024");
        assertThat(result.input().jobDescription())
            .doesNotContain("recruiter@example.org", "+65 6123 4567", "年龄：31");
        assertThat(result.input().skillTags()).containsExactly("Java", "[REDACTED_EMAIL]");
        assertThat(result.redactionCounts())
            .containsEntry(ModelInputPrivacySanitizer.PiiType.EMAIL, 3)
            .containsEntry(ModelInputPrivacySanitizer.PiiType.PHONE, 2)
            .containsEntry(ModelInputPrivacySanitizer.PiiType.GOVERNMENT_ID, 1)
            .containsEntry(ModelInputPrivacySanitizer.PiiType.NAME, 1)
            .containsEntry(ModelInputPrivacySanitizer.PiiType.ADDRESS, 1)
            .containsEntry(ModelInputPrivacySanitizer.PiiType.PROTECTED_ATTRIBUTE, 2);
    }

    @Test
    void leavesOrdinaryTechnicalNumbersAndUnlabelledNamesUntouched() {
        AnalysisInput input = new AnalysisInput(
            8L,
            "Built Java 21 services from 2022-2024 with Redis 7 and 99.9% availability.",
            "AI Engineer",
            "Need GPT-4.1, Python 3.12 and at least 5 years experience.",
            List.of("Java 21", "GPT-4.1"),
            "correlation-8"
        );

        ModelInputPrivacySanitizer.SanitizationResult result = sanitizer.sanitize(input);

        assertThat(result.input()).isEqualTo(input);
        assertThat(result.redactionCounts()).isEmpty();
    }

    @Test
    void producesIdenticalProviderInputForOneHundredProtectedAttributeCounterfactualPairs() {
        String[] firstNames = {"张三", "王芳", "赵敏", "陈晨", "周宁"};
        String[] secondNames = {"李四", "刘洋", "孙悦", "杨帆", "吴昊"};
        String[] firstGenders = {"男", "女", "非二元"};
        String[] secondGenders = {"女", "非二元", "男"};

        for (int index = 0; index < 100; index++) {
            String sharedEvidence = "Java 21、Spring Boot、Redis，负责高可用服务与自动化测试。";
            AnalysisInput first = counterfactualInput(
                firstNames[index % firstNames.length],
                firstGenders[index % firstGenders.length],
                20 + index % 30,
                sharedEvidence
            );
            AnalysisInput second = counterfactualInput(
                secondNames[index % secondNames.length],
                secondGenders[index % secondGenders.length],
                50 + index % 20,
                sharedEvidence
            );

            assertThat(first.resumeText()).isNotEqualTo(second.resumeText());
            assertThat(sanitizer.sanitize(first).input())
                .as("counterfactual pair %s must be identical at the provider boundary", index)
                .isEqualTo(sanitizer.sanitize(second).input());
        }
    }

    private AnalysisInput counterfactualInput(String name, String gender, int age, String evidence) {
        return new AnalysisInput(
            99L,
            "姓名：" + name + "\n性别：" + gender + "\n年龄：" + age + "\n" + evidence,
            "Backend Engineer",
            "需要 Java、Spring Boot、Redis 与自动化测试经验。",
            List.of("Java", "Spring Boot", "Redis"),
            "fairness-counterfactual"
        );
    }
}
