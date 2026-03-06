package com.sqlcopilot.studio.service.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/** RAG 各层级集合名称配置。 */
@Data
@AllArgsConstructor
public class RagCollectionNames {

    /** 表级语义集合。 */
    private String schemaTable;

    /** 字段级语义集合。 */
    private String schemaColumn;

    /** SQL 历史语义集合。 */
    private String sqlHistory;

    /** 指标术语语义集合。 */
    private String metricTerm;

    /** SQL 样例语义集合。 */
    private String exampleSql;

    /** SQL 片段语义集合。 */
    private String sqlFragment;
}
