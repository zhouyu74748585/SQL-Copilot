package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/** ER 连线端点在卡片周长上的锚点位置。 */
@Data
public class ErRelationAnchorOffsetVO {

    /** 源端点周长归一化位置（0~1）。 */
    private Double sourcePerimeterPos;

    /** 目标端点周长归一化位置（0~1）。 */
    private Double targetPerimeterPos;
}
