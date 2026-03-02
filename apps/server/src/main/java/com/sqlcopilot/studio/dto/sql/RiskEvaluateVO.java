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

    /** 高风险确认令牌，高风险时返回。 */
    private String riskAckToken;
}
