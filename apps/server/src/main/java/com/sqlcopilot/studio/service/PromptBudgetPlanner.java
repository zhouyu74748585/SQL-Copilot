package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.ai.PromptBudgetVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 最终 prompt 预算规划器。 */
@Service
public class PromptBudgetPlanner {

    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32000;
    private static final int DEFAULT_COMPLETION_RESERVE_TOKENS = 2048;
    private static final int SAFETY_MARGIN_TOKENS = 512;

    private final TokenEstimatorService tokenEstimatorService;

    public PromptBudgetPlanner(TokenEstimatorService tokenEstimatorService) {
        this.tokenEstimatorService = tokenEstimatorService;
    }

    /** 在发送到模型前，对最终 prompt 做统一预算与裁剪。 */
    public PromptBudgetPlan plan(PromptBudgetRequest request) {
        String systemPrompt = safe(request.systemPrompt());
        String tokenizerType = safe(request.tokenizerType());
        int contextWindowTokens = clamp(request.contextWindowTokens(), 2048, 256000, DEFAULT_CONTEXT_WINDOW_TOKENS);
        int completionReserveTokens = clamp(request.completionReserveTokens(), 256, 64000, DEFAULT_COMPLETION_RESERVE_TOKENS);
        int promptBudgetTokens = Math.max(256, contextWindowTokens - completionReserveTokens - SAFETY_MARGIN_TOKENS);

        PromptSegment databaseInfo = new PromptSegment("数据库基本信息", safe(request.databaseInfo()), true);
        PromptSegment userNeed = new PromptSegment("用户需求", safe(request.userNeed()), true);
        PromptSegment guardrails = new PromptSegment("表使用硬约束", safe(request.tableGuardrails()), true);
        PromptSegment retrieval = new PromptSegment("检索增强输入(含会话记忆)", safe(request.retrievalInput()), true);
        PromptSegment recentSummary = new PromptSegment("Conversation Recent Summary", safe(request.windowSummary()), false);
        PromptSegment slidingSummary = new PromptSegment("Conversation Sliding Summary", safe(request.slidingSummary()), false);
        PromptSegment structuredContext = new PromptSegment("Conversation Window Context(JSON)", safe(request.windowStructuredContext()), false);
        PromptSegment rawContext = new PromptSegment("Conversation Recent Raw", safe(request.windowDialogContext()), false);
        PromptSegment knowledgeContext = new PromptSegment("RAG Context", safe(request.knowledgeContext()), false);

        List<PromptSegment> baseSegments = List.of(databaseInfo, userNeed, guardrails, retrieval);
        List<PromptSegment> optionalSegments = new ArrayList<>(List.of(recentSummary, slidingSummary, structuredContext, rawContext, knowledgeContext));

        String prompt = composePrompt(baseSegments, optionalSegments);
        int promptTokens = estimatePromptTokens(systemPrompt, prompt, tokenizerType);
        if (promptTokens > promptBudgetTokens) {
            trimSegmentToBudget(systemPrompt, rawContext, baseSegments, optionalSegments, tokenizerType, promptBudgetTokens, true);
            prompt = composePrompt(baseSegments, optionalSegments);
            promptTokens = estimatePromptTokens(systemPrompt, prompt, tokenizerType);
        }
        if (promptTokens > promptBudgetTokens) {
            trimSegmentToBudget(systemPrompt, slidingSummary, baseSegments, optionalSegments, tokenizerType, promptBudgetTokens, false);
            prompt = composePrompt(baseSegments, optionalSegments);
            promptTokens = estimatePromptTokens(systemPrompt, prompt, tokenizerType);
        }
        if (promptTokens > promptBudgetTokens) {
            trimSegmentToBudget(systemPrompt, structuredContext, baseSegments, optionalSegments, tokenizerType, promptBudgetTokens, false);
            prompt = composePrompt(baseSegments, optionalSegments);
            promptTokens = estimatePromptTokens(systemPrompt, prompt, tokenizerType);
        }
        if (promptTokens > promptBudgetTokens) {
            trimSegmentToBudget(systemPrompt, knowledgeContext, baseSegments, optionalSegments, tokenizerType, promptBudgetTokens, false);
            prompt = composePrompt(baseSegments, optionalSegments);
            promptTokens = estimatePromptTokens(systemPrompt, prompt, tokenizerType);
        }
        if (promptTokens > promptBudgetTokens) {
            trimSegmentToBudget(systemPrompt, recentSummary, baseSegments, optionalSegments, tokenizerType, promptBudgetTokens, false);
            prompt = composePrompt(baseSegments, optionalSegments);
            promptTokens = estimatePromptTokens(systemPrompt, prompt, tokenizerType);
        }

        PromptBudgetVO budget = new PromptBudgetVO();
        budget.setContextWindowTokens(contextWindowTokens);
        budget.setCompletionReserveTokens(completionReserveTokens);
        budget.setSafetyMarginTokens(SAFETY_MARGIN_TOKENS);
        budget.setPromptBudgetTokens(promptBudgetTokens);
        budget.setPromptTokens(promptTokens);
        budget.setMemoryWindowUsedTokens(Math.max(0, request.memoryWindowUsedTokens()));
        budget.setMemoryWindowBudgetTokens(Math.max(0, request.memoryWindowBudgetTokens()));
        budget.setTokenizerType(tokenizerType.isBlank() ? TokenEstimatorService.TOKENIZER_GENERIC_HEURISTIC : tokenizerType);
        budget.setOverBudget(promptTokens > promptBudgetTokens);
        return new PromptBudgetPlan(
            prompt,
            budget,
            rawContext.text(),
            recentSummary.text(),
            slidingSummary.text(),
            structuredContext.text(),
            knowledgeContext.text()
        );
    }

    private int estimatePromptTokens(String systemPrompt, String userPrompt, String tokenizerType) {
        String combined = systemPrompt.isBlank() ? userPrompt : systemPrompt + "\n\n" + userPrompt;
        return tokenEstimatorService.estimateTokens(combined, tokenizerType);
    }

    private void trimSegmentToBudget(String systemPrompt,
                                     PromptSegment target,
                                     List<PromptSegment> baseSegments,
                                     List<PromptSegment> optionalSegments,
                                     String tokenizerType,
                                     int promptBudgetTokens,
                                     boolean preserveTail) {
        if (target.text().isBlank()) {
            return;
        }
        String original = target.text();
        int left = 0;
        int right = original.length();
        String best = "";
        while (left <= right) {
            int middle = (left + right) >>> 1;
            String candidate = preserveTail ? original.substring(Math.max(0, original.length() - middle)) : original.substring(0, middle);
            target.setText(candidate);
            int promptTokens = estimatePromptTokens(systemPrompt, composePrompt(baseSegments, optionalSegments), tokenizerType);
            if (promptTokens <= promptBudgetTokens) {
                best = candidate;
                left = middle + 1;
            } else {
                right = middle - 1;
            }
        }
        target.setText(best);
    }

    private String composePrompt(List<PromptSegment> baseSegments, List<PromptSegment> optionalSegments) {
        List<String> sections = new ArrayList<>();
        for (PromptSegment segment : baseSegments) {
            if (!segment.text().isBlank()) {
                sections.add(segment.title() + ":\n" + segment.text());
            }
        }
        for (PromptSegment segment : optionalSegments) {
            if (!segment.text().isBlank()) {
                sections.add(segment.title() + ":\n" + segment.text());
            }
        }
        return String.join("\n\n", sections);
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        int actual = value == null ? fallback : value;
        return Math.max(min, Math.min(actual, max));
    }

    private String safe(String value) {
        return Objects.toString(value, "").trim();
    }

    public record PromptBudgetRequest(String systemPrompt,
                                      String databaseInfo,
                                      String userNeed,
                                      String tableGuardrails,
                                      String retrievalInput,
                                      String windowSummary,
                                      String slidingSummary,
                                      String windowStructuredContext,
                                      String windowDialogContext,
                                      String knowledgeContext,
                                      Integer contextWindowTokens,
                                      Integer completionReserveTokens,
                                      int memoryWindowUsedTokens,
                                      int memoryWindowBudgetTokens,
                                      String tokenizerType) {
    }

    public record PromptBudgetPlan(String userPrompt,
                                   PromptBudgetVO budget,
                                   String rawContext,
                                   String windowSummary,
                                   String slidingSummary,
                                   String windowStructuredContext,
                                   String knowledgeContext) {
    }

    private static final class PromptSegment {
        private final String title;
        private String text;
        @SuppressWarnings("unused")
        private final boolean required;

        private PromptSegment(String title, String text, boolean required) {
            this.title = title;
            this.text = Objects.toString(text, "").trim();
            this.required = required;
        }

        private String title() {
            return title;
        }

        private String text() {
            return text;
        }

        private void setText(String text) {
            this.text = Objects.toString(text, "").trim();
        }
    }
}
