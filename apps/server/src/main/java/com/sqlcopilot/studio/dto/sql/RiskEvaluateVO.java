package com.sqlcopilot.studio.dto.sql;

import lombok.Data;

import java.util.List;

/** SQL 风险评估响应对象。 */
@Data
public class RiskEvaluateVO {

    /** 风险级别：LOW/MEDIUM/HIGH。 */
    private String riskLevel;

    /** 风险明细列表。 */
    private List<RiskItemVO> riskItems;

    /** 是否需要执行前二次确认。 */
    private Boolean confirmRequired;

    /** 二次确认原因编码（如 PROD_MEDIUM_PLUS / HIGH_RISK）。 */
    private String confirmReason;

    /** 风险确认令牌，需要确认时返回。 */
    private String riskAckToken;
}
