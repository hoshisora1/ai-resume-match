package com.zhulikang.aimatch.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveTelemetryPolicyTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final List<String> SENSITIVE_EXPRESSIONS = List.of(
        "getrawtext(",
        "getcontent(",
        "resumetext(",
        "jobdescription(",
        "reportcontent(",
        "resume_text",
        "job_description",
        "report_markdown",
        "structured_report",
        "request.body",
        "getresponsebodyasstring(",
        "model_dump(",
        "response.text",
        "response.content",
        "getmessage(",
        "str(exc",
        "str(exception"
    );

    @Test
    void productionTelemetryStatementsDoNotReferenceDocumentOrModelBodies() throws IOException {
        List<Path> files = new ArrayList<>();
        collect(files, ROOT.resolve("src/main/java"), ".java");
        collect(files, ROOT.resolve("agent-service/agent_service"), ".py");

        List<String> violations = new ArrayList<>();
        int inspectedStatements = 0;
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (!startsTelemetryStatement(line)) {
                    continue;
                }
                inspectedStatements++;
                String statement = collectStatement(lines, index).toLowerCase(Locale.ROOT);
                for (String expression : SENSITIVE_EXPRESSIONS) {
                    if (statement.contains(expression)) {
                        violations.add(relative(file) + ":" + (index + 1) + " references " + expression);
                    }
                }
                if (statement.matches("(?s).*,\\s*(?:ex|exc|exception|throwable)\\s*[),].*")) {
                    violations.add(relative(file) + ":" + (index + 1) + " passes a throwable to telemetry");
                }
            }
        }

        assertThat(inspectedStatements).isGreaterThan(20);
        assertThat(violations)
            .as("production telemetry must contain identifiers, classifications and counts only")
            .isEmpty();
    }

    private void collect(List<Path> files, Path root, String suffix) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(suffix))
                .forEach(files::add);
        }
    }

    private boolean startsTelemetryStatement(String line) {
        String normalized = line.stripLeading();
        return normalized.matches(
            "(?i)(?:log|logger)\\.(?:trace|debug|info|warn|warning|error|exception|critical)\\s*\\(.*"
        )
            || normalized.startsWith("Counter.builder(")
            || normalized.startsWith("Timer.builder(")
            || normalized.startsWith(".tag(")
            || normalized.matches(".*(?:Counter|Histogram|Gauge)\\s*\\(.*")
            || normalized.matches("(?:self\\._metrics|metrics)\\..*")
            || normalized.matches("self\\._.*\\.labels\\(.*")
            || normalized.contains(".setAttribute(")
            || normalized.contains(".addEvent(");
    }

    private String collectStatement(List<String> lines, int start) {
        StringBuilder statement = new StringBuilder();
        int balance = 0;
        boolean sawOpeningParenthesis = false;
        for (int index = start; index < Math.min(lines.size(), start + 30); index++) {
            String line = lines.get(index);
            statement.append(line).append('\n');
            for (int offset = 0; offset < line.length(); offset++) {
                char value = line.charAt(offset);
                if (value == '(') {
                    balance++;
                    sawOpeningParenthesis = true;
                } else if (value == ')') {
                    balance--;
                }
            }
            if (sawOpeningParenthesis && balance <= 0) {
                break;
            }
        }
        return statement.toString();
    }

    private String relative(Path file) {
        return ROOT.relativize(file).toString().replace('\\', '/');
    }
}
