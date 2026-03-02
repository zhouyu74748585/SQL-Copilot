package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 生成 SQL 响应对象。 */
@Data
public class AiGenerateSqlVO {

    /** AI 生成的 SQL 文本。 */
    private String sqlText;

    /** 生成理由说明。 */
    private String reasoning;

    /** 当前是否触发降级模型。 */
    private Boolean fallbackUsed;
}
