package com.sqlcopilot.studio.dto.kv;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** KV/文档查询执行请求。 */
@Data
public class KvQueryExecuteReq {

    /** 连接 ID。 */
    @NotNull
    private Long connectionId;

    /** 会话 ID。 */
    private String sessionId;

    /** 当前目标数据库。 */
    private String databaseName;

    /** 查询文本。 */
    @NotBlank
    private String queryText;

    /** 最大返回行数。 */
    private Integer maxRows;
}
