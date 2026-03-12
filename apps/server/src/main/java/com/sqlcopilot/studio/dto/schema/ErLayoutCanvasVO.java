package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/** ER 布局使用的固定画布尺寸。 */
@Data
public class ErLayoutCanvasVO {

    /** 逻辑画布宽度。 */
    private Double width;

    /** 逻辑画布高度。 */
    private Double height;
}
