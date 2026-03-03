package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 修复 SQL 响应对象。 */
@Data
public class AiRepairVO {

    /** 修复后的 SQL 文本。 */
    private String repairedSql;

    /** 是否成功修复。 */
    private Boolean repaired;

    /** 修复说明。 */
    private String repairNote;

    /** 错误原因说明（文本段）。 */
    private String errorExplanation;
}
