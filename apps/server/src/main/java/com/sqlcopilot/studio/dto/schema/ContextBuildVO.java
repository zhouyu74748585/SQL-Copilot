package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

/** 构建 Schema 上下文响应对象。 */
@Data
public class ContextBuildVO {

    /** 最终上下文文本。 */
    private String context;

    /** 估算消耗 Token。 */
    private Integer usedTokens;

    /** 命中的相关表名列表。 */
    private List<String> relatedTables;
}
