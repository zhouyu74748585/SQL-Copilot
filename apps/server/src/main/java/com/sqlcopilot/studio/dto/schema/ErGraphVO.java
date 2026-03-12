package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ErGraphVO {

    private Long connectionId;

    private String databaseName;

    private List<ErTableNodeVO> tables;

    private List<ErRelationVO> foreignKeyRelations;

    private List<ErRelationVO> aiRelations;

    private ErAiInferenceStatusVO aiInference;

    /** 固定布局画布尺寸（用于历史回显时保持绝对布局）。 */
    private ErLayoutCanvasVO layoutCanvas;

    /** 手动调整后的节点中心点坐标（key 为规范化表名）。 */
    private Map<String, ErNodePositionVO> nodePositions;

    /** 连线端点锚点偏移（key 为关系唯一键）。 */
    private Map<String, ErRelationAnchorOffsetVO> relationAnchorOffsets;

    private Long generatedAt;
}
