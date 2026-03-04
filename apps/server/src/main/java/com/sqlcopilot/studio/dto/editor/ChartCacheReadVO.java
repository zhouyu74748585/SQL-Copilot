package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

/** 图表图片缓存读取响应对象。 */
@Data
public class ChartCacheReadVO {

    /** 缓存键。 */
    private String cacheKey;

    /** Data URL 形式图片文本。 */
    private String dataUrl;
}
