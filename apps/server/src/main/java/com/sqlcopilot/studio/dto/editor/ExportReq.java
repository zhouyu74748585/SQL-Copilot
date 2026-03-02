package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 查询结果导出请求对象。 */
@Data
public class ExportReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 需要导出的 SQL。 */
    @NotBlank
    private String sqlText;

    /** 导出格式：CSV/JSON。 */
    @NotBlank
    private String format;

    /** 目标文件名（不含路径）。 */
    private String fileName;
}
