package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

/** ER 图快照摘要响应对象。 */
@Data
public class ErGraphSnapshotSummaryVO {

    /** 快照主键 ID。 */
    private Long id;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 快照名称。 */
    private String snapshotName;

    /** 选中表数量。 */
    private Integer tableCount;

    /** 模型标识。 */
    private String modelName;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;

    /** 更新时间戳（毫秒）。 */
    private Long updatedAt;
}
