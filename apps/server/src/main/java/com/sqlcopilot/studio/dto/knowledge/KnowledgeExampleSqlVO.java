package com.sqlcopilot.studio.dto.knowledge;

import lombok.Data;

import java.util.List;

/** 样例 SQL 响应对象。 */
@Data
public class KnowledgeExampleSqlVO {

    /** 样例主键 ID。 */
    private Long id;

    /** 作用域：GLOBAL/CONNECTION/DATABASE。 */
    private String scope;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** SQL 正文。 */
    private String sqlText;

    /** 样例说明。 */
    private String description;

    /** 关联术语 ID 集合。 */
    private List<Long> termIds;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;

    /** 更新时间戳（毫秒）。 */
    private Long updatedAt;
}
