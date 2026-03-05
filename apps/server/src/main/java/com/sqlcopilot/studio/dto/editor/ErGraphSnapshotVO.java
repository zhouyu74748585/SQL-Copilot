package com.sqlcopilot.studio.dto.editor;

import com.sqlcopilot.studio.dto.schema.ErGraphVO;
import lombok.Data;

import java.util.List;

/** ER 图快照详情响应对象。 */
@Data
public class ErGraphSnapshotVO {

    /** 快照主键 ID。 */
    private Long id;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 快照名称。 */
    private String snapshotName;

    /** 选中的表名列表。 */
    private List<String> selectedTableNames;

    /** 模型标识。 */
    private String modelName;

    /** 布局模式。 */
    private String layoutMode;

    /** AI 推断置信度阈值。 */
    private Double aiConfidenceThreshold;

    /** 是否包含 AI 推断。 */
    private Boolean includeAiInference;

    /** ER 图结果对象。 */
    private ErGraphVO graph;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;

    /** 更新时间戳（毫秒）。 */
    private Long updatedAt;
}
