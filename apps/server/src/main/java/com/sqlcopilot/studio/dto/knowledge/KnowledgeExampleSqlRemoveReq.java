package com.sqlcopilot.studio.dto.knowledge;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除样例 SQL 请求对象。 */
@Data
public class KnowledgeExampleSqlRemoveReq {

    /** 样例主键 ID。 */
    @NotNull
    private Long id;
}
