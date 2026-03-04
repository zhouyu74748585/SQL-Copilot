package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** 单表手动向量化响应对象。 */
@Data
public class RagVectorizeTableVO {

    /** 是否执行成功。 */
    private Boolean success;

    /** 数据库名称。 */
    private String databaseName;

    /** 表名。 */
    private String tableName;

    /** 结果说明。 */
    private String message;

    /** 更新时间（毫秒时间戳）。 */
    private Long updatedAt;
}
