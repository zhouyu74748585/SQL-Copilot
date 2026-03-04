package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

/** 图表图片缓存保存响应对象。 */
@Data
public class ChartCacheSaveVO {

    /** 缓存键。 */
    private String cacheKey;

    /** 文件绝对路径。 */
    private String filePath;

    /** 图片宽度。 */
    private Integer width;

    /** 图片高度。 */
    private Integer height;
}
