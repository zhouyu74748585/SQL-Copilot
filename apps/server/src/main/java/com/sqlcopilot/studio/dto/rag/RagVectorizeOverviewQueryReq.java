package com.sqlcopilot.studio.dto.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 向量化概要查询请求对象。 */
@Data
public class RagVectorizeOverviewQueryReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 数据库名称。 */
    @NotBlank
    private String databaseName;
}
