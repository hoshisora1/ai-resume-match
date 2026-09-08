package com.zhulikang.aimatch.application.analysis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ModelInputPrivacySanitizer {
    private static final Pattern EMAIL = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}(?![\\p{L}\\p{N}._%+-])"
    );
    private static final Pattern CHINESE_NATIONAL_ID = Pattern.compile("(?i)(?<!\\d)\\d{17}[\\dX](?![\\p{L}\\p{N}])");
    private static final Pattern CHINESE_MOBILE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern FORMATTED_PHONE = Pattern.compile(
        "(?<![\\p{L}\\p{N}])(?:\\+\\d{1,3}[-. ]?)?(?:\\(\\d{2,4}\\)|\\d{2,4})[-. ]\\d{3,4}[-. ]\\d{4}(?!\\d)"
    );
    private static final Pattern CHINESE_NAME = Pattern.compile("(?iu)(姓名\\s*[:：]\\s*)[\\p{IsHan}·]{2,20}");
    private static final Pattern ENGLISH_NAME = Pattern.compile(
        "(?i)(\\b(?:full\\s*name|name)\\s*[:：]\\s*)[A-Z][A-Z'’-]{0,30}(?:\\s+[A-Z][A-Z'’-]{0,30}){0,3}"
    );
    private static final Pattern ADDRESS = Pattern.compile(
        "(?imu)((?:\\baddress|地址)\\s*[:：]\\s*)[^\\r\\n|;；]{1,160}"
    );
    private static final Pattern PROTECTED_ATTRIBUTE = Pattern.compile(
        "(?iu)((?:\\bgender|\\bsex|\\bage|\\bdate\\s+of\\s+birth|\\bbirthdate|\\bmarital\\s+status|性别|年龄|出生日期|婚姻状况)\\s*[:：]\\s*)[^\\s,，;；|]{1,40}"
    );

    public SanitizationResult sanitize(AnalysisInput input) {
        EnumMap<PiiType, Integer> totals = new EnumMap<>(PiiType.class);
        FieldResult resume = sanitizeText(input.resumeText(), totals);
        FieldResult title = sanitizeText(input.jobTitle(), totals);
        FieldResult description = sanitizeText(input.jobDescription(), totals);
        List<String> tags = new ArrayList<>(input.skillTags().size());
        for (String tag : input.skillTags()) {
            tags.add(sanitizeText(tag, totals).text());
        }
        return new SanitizationResult(
            new AnalysisInput(
                input.taskId(),
                resume.text(),
                title.text(),
                description.text(),
                tags,
                input.correlationId()
            ),
            Map.copyOf(totals)
        );
    }

    private FieldResult sanitizeText(String source, EnumMap<PiiType, Integer> totals) {
        String value = source;
        value = replace(value, EMAIL, PiiType.EMAIL, totals, false);
        value = replace(value, CHINESE_NATIONAL_ID, PiiType.GOVERNMENT_ID, totals, false);
        value = replace(value, CHINESE_MOBILE, PiiType.PHONE, totals, false);
        value = replace(value, FORMATTED_PHONE, PiiType.PHONE, totals, false);
        value = replace(value, CHINESE_NAME, PiiType.NAME, totals, true);
        value = replace(value, ENGLISH_NAME, PiiType.NAME, totals, true);
        value = replace(value, ADDRESS, PiiType.ADDRESS, totals, true);
        value = replace(value, PROTECTED_ATTRIBUTE, PiiType.PROTECTED_ATTRIBUTE, totals, true);
        return new FieldResult(value);
    }

    private String replace(
        String source,
        Pattern pattern,
        PiiType type,
        EnumMap<PiiType, Integer> totals,
        boolean preserveLabel
    ) {
        Matcher matcher = pattern.matcher(source);
        StringBuffer result = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            String replacement = (preserveLabel ? matcher.group(1) : "") + "[REDACTED_" + type.name() + "]";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            count++;
        }
        if (count == 0) {
            return source;
        }
        matcher.appendTail(result);
        totals.merge(type, count, Integer::sum);
        return result.toString();
    }

    public enum PiiType {
        EMAIL,
        PHONE,
        GOVERNMENT_ID,
        NAME,
        ADDRESS,
        PROTECTED_ATTRIBUTE
    }

    public record SanitizationResult(AnalysisInput input, Map<PiiType, Integer> redactionCounts) {
    }

    private record FieldResult(String text) {
    }
}
