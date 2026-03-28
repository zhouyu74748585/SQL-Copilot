package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

/** 统一 token 估算服务。 */
@Service
public class TokenEstimatorService {

    public static final String TOKENIZER_GENERIC_HEURISTIC = "GENERIC_HEURISTIC";
    public static final String TOKENIZER_OPENAI_COMPAT = "OPENAI_COMPAT";
    public static final String TOKEN_SOURCE_PROVIDER_USAGE = "provider_usage";
    public static final String TOKEN_SOURCE_BACKEND_ESTIMATOR = "backend_estimator";
    public static final String TOKEN_SOURCE_HEURISTIC = "heuristic";
    public static final String TOKEN_SCOPE_TURN_CONTENT = "TURN_CONTENT";
    public static final String TOKEN_SCOPE_REQUEST_TOTAL = "REQUEST_TOTAL";
    public static final String TOKEN_SCOPE_LEGACY_REQUEST_TOTAL = "LEGACY_REQUEST_TOTAL";
    public static final int TOKEN_ESTIMATE_VERSION = 2;

    /** 统一估算纯文本 token。 */
    public int estimateTokens(String text) {
        return estimateTokens(text, TOKENIZER_GENERIC_HEURISTIC);
    }

    /** 按 tokenizer 类型估算纯文本 token。 */
    public int estimateTokens(String text, String tokenizerType) {
        String normalized = raw(text);
        if (normalized.isBlank()) {
            return 0;
        }
        String resolvedTokenizer = normalizeTokenizerType(tokenizerType);
        double tokens = TOKENIZER_OPENAI_COMPAT.equals(resolvedTokenizer)
            ? estimateOpenAiCompatTokens(normalized)
            : estimateGenericTokens(normalized);
        return Math.max(1, (int) Math.ceil(tokens));
    }

    /** 估算单条历史内容 token。 */
    public int estimateTurnContentTokens(String promptText,
                                         String sqlText,
                                         String assistantContent,
                                         String chartConfigJson) {
        StringBuilder builder = new StringBuilder();
        appendSegment(builder, "U", promptText);
        appendSegment(builder, "SQL", sqlText);
        appendSegment(builder, "A", assistantContent);
        appendSegment(builder, "CHART", chartConfigJson);
        return estimateTokens(builder.toString(), TOKENIZER_OPENAI_COMPAT);
    }

    /** 统一估算历史记录内容 token。 */
    public int estimateTurnContentTokens(QueryHistoryEntity item) {
        if (item == null) {
            return 0;
        }
        Integer stored = item.getTurnContentTokens();
        if (stored != null && stored > 0) {
            return stored;
        }
        return estimateTurnContentTokens(
            item.getPromptText(),
            item.getSqlText(),
            item.getAssistantContent(),
            item.getChartConfigJson()
        );
    }

    /** 判断是否为启发式估算 token。 */
    public boolean isEstimated(String source) {
        String normalized = safe(source).toLowerCase(Locale.ROOT);
        return normalized.isBlank()
            || TOKEN_SOURCE_BACKEND_ESTIMATOR.equals(normalized)
            || TOKEN_SOURCE_HEURISTIC.equals(normalized);
    }

    private double estimateOpenAiCompatTokens(String text) {
        double tokens = 0D;
        int asciiRun = 0;
        int digitRun = 0;
        int whitespaceRun = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isWhitespace(ch)) {
                asciiRun = 0;
                digitRun = 0;
                whitespaceRun++;
                if (whitespaceRun == 1) {
                    tokens += 0.15D;
                }
                continue;
            }
            whitespaceRun = 0;
            if (isCjk(ch)) {
                asciiRun = 0;
                digitRun = 0;
                tokens += 1.45D;
                continue;
            }
            if (Character.isDigit(ch)) {
                asciiRun = 0;
                digitRun++;
                if (digitRun == 1) {
                    tokens += 0.55D;
                } else if (digitRun % 3 == 0) {
                    tokens += 0.35D;
                }
                continue;
            }
            if (isAsciiWord(ch)) {
                digitRun = 0;
                asciiRun++;
                if (asciiRun == 1) {
                    tokens += 0.55D;
                } else if (asciiRun % 4 == 0) {
                    tokens += 0.35D;
                }
                continue;
            }
            asciiRun = 0;
            digitRun = 0;
            tokens += isCommonPunctuation(ch) ? 0.5D : 0.8D;
        }
        return Math.max(tokens, Math.ceil(text.trim().length() / 5.0D));
    }

    private double estimateGenericTokens(String text) {
        double tokens = 0D;
        int compactRun = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isWhitespace(ch)) {
                compactRun = 0;
                tokens += 0.1D;
                continue;
            }
            if (isCjk(ch)) {
                compactRun = 0;
                tokens += 1.2D;
                continue;
            }
            compactRun++;
            if (isCommonPunctuation(ch)) {
                compactRun = 0;
                tokens += 0.4D;
                continue;
            }
            if (compactRun == 1) {
                tokens += 0.45D;
            } else if (compactRun % 4 == 0) {
                tokens += 0.25D;
            }
        }
        return Math.max(tokens, Math.ceil(text.trim().length() / 6.0D));
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.HIRAGANA
            || block == Character.UnicodeBlock.KATAKANA
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES
            || block == Character.UnicodeBlock.HANGUL_JAMO;
    }

    private boolean isAsciiWord(char ch) {
        return ch < 128 && (Character.isLetter(ch) || ch == '_' || ch == '-' || ch == '/');
    }

    private boolean isCommonPunctuation(char ch) {
        return ",.;:!?()[]{}<>\"'`+-=*|\\#@$%^&".indexOf(ch) >= 0;
    }

    private void appendSegment(StringBuilder builder, String label, String text) {
        String value = raw(text).trim();
        if (value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(value);
    }

    private String normalizeTokenizerType(String tokenizerType) {
        String normalized = safe(tokenizerType).toUpperCase(Locale.ROOT);
        if (TOKENIZER_OPENAI_COMPAT.equals(normalized)) {
            return TOKENIZER_OPENAI_COMPAT;
        }
        return TOKENIZER_GENERIC_HEURISTIC;
    }

    private String safe(String value) {
        return Objects.toString(value, "").trim();
    }

    private String raw(String value) {
        return Objects.toString(value, "");
    }
}
