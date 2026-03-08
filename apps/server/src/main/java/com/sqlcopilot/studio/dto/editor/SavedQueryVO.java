package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

/** 已保存查询响应对象。 */
@Data
public class SavedQueryVO {

    /** 主键 ID。 */
    private Long id;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 保存名称。 */
    private String title;

    /** SQL 正文。 */
    private String sqlText;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;

    /** 更新时间戳（毫秒）。 */
    private Long updatedAt;
}
