package com.sqlcopilot.studio.service.rag.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** RAG 检索后生成的 Prompt 上下文对象。 */
@Data
public class RagPromptContext {

    /** 最终用于 LLM 的上下文文本。 */
    private String promptContext;

    /** 命中的表名列表。 */
    private List<String> relatedTables;

    /** 命中的字段列表（table.column）。 */
    private List<String> relatedColumns;

    /** 命中的历史 SQL 示例。 */
    private List<String> historySqlSamples;

    /** 是否命中任何向量结果。 */
    private Boolean hit;

    private Boolean rerankEnabled;

    private String rerankProvider;

    private List<Map<String, Object>> rerankDetails;

    /** 召回筛选与门控明细。 */
    private List<Map<String, Object>> selectionDetails;

    /** 组装到 Prompt 中的上下文 token 估算。 */
    private Integer promptBudgetUsed;
}
