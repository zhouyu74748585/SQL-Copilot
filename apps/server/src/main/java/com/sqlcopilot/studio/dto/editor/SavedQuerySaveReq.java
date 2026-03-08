package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 保存查询请求对象。 */
@Data
public class SavedQuerySaveReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 保存名称。 */
    @NotBlank
    private String title;

    /** SQL 正文。 */
    @NotBlank
    private String sqlText;
}
