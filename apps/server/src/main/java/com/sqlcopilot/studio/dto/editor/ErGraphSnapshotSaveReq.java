package com.sqlcopilot.studio.dto.editor;

import com.sqlcopilot.studio.dto.schema.ErGraphVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

/** 保存 ER 图快照请求对象。 */
@Data
public class ErGraphSnapshotSaveReq {

    /** 快照主键 ID（存在时表示更新已保存快照）。 */
    @Positive
    private Long snapshotId;

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 数据库名称。 */
    @NotBlank
    private String databaseName;

    /** 快照名称（由用户输入）。 */
    @NotBlank
    private String snapshotName;

    /** 选中的表名列表。 */
    private List<String> selectedTableNames;

    /** 当前选择的 AI 模型标识。 */
    private String modelName;

    /** ER 图布局模式。 */
    private String layoutMode;

    /** AI 推断置信度阈值。 */
    private Double aiConfidenceThreshold;

    /** 是否包含 AI 推断。 */
    private Boolean includeAiInference;

    /** ER 图结果对象。 */
    @NotNull
    private ErGraphVO graph;
}
