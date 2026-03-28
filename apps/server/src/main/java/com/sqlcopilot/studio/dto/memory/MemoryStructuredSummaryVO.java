package com.sqlcopilot.studio.dto.memory;

import lombok.Data;

import java.util.List;

/** 结构化长期记忆摘要。 */
@Data
public class MemoryStructuredSummaryVO {

    /** 顶层记忆类型：SESSION_SUMMARY / CORRECTION / PRIORITY_HINT / MANUAL。 */
    private String memoryType;

    /** 沉淀出的稳定事实。 */
    private List<String> facts;

    /** 硬约束或默认限制。 */
    private List<String> constraints;

    /** 对已有理解的纠正信息。 */
    private List<String> corrections;

    /** 用户强调的重点提示。 */
    private List<String> priorityHints;

    /** 与当前记忆相关的表。 */
    private List<String> relatedTables;

    /** 记忆作用域。 */
    private String scope;

    /** 来源历史记录 ID。 */
    private List<Long> sourceHistoryIds;

    /** 被本条记忆覆盖的旧记忆 ID。 */
    private List<Long> supersedesMemoryIds;

    /** 模型给出的摘要置信度。 */
    private Double confidence;

    /** 对外展示和向量化使用的文本摘要。 */
    private String summaryText;
}
