package com.sqlcopilot.studio.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** 保存样例 SQL 请求对象。 */
@Data
public class KnowledgeExampleSqlSaveReq {

    /** 样例主键 ID，编辑时传入。 */
    private Long id;

    /** 作用域：GLOBAL/CONNECTION/DATABASE。 */
    @NotBlank
    private String scope;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** SQL 正文。 */
    @NotBlank
    private String sqlText;

    /** 样例说明。 */
    private String description;

    /** 关联术语 ID 集合。 */
    private List<Long> termIds;
}
