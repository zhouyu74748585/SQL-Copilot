package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 图表图片缓存保存请求对象。 */
@Data
public class ChartCacheSaveReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 会话 ID。 */
    @NotBlank
    private String sessionId;

    /** PNG 图片 Base64 文本（可带 data:image/png;base64, 前缀）。 */
    @NotBlank
    private String imageBase64Png;

    /** 建议文件名（可选）。 */
    private String suggestedFileName;

    /** 图片宽度（可选）。 */
    private Integer width;

    /** 图片高度（可选）。 */
    private Integer height;
}
