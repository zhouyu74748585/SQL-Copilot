package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

/** 查询结果导出响应对象。 */
@Data
public class ExportResultVO {

    /** 是否导出成功。 */
    private Boolean success;

    /** 导出文件绝对路径。 */
    private String filePath;

    /** 导出结果说明。 */
    private String message;
}
