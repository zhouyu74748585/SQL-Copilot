package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 保存查询历史请求对象。 */
@Data
public class SaveQueryHistoryReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 会话 ID。 */
    @NotBlank
    private String sessionId;

    /** 用户输入提示词。 */
    private String promptText;

    /** SQL 文本。 */
    @NotBlank
    private String sqlText;

    /** 历史类型：CHAT/EXECUTE。 */
    private String historyType;

    /** 动作类型：generate/explain/analyze/repair/chart_auto_plan/chart_manual_render/chart_auto_render。 */
    private String actionType;

    /** 助手文本内容（可选）。 */
    private String assistantContent;

    /** 当前数据库名（可选）。 */
    private String databaseName;

    /** 图表配置 JSON 文本（可选）。 */
    private String chartConfigJson;

    /** 图表缓存图片键（可选）。 */
    private String chartImageCacheKey;

    /** 执行耗时（毫秒）。 */
    private Long executionMs;

    /** 是否执行成功。 */
    @NotNull
    private Boolean success;
}
