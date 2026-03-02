package com.sqlcopilot.studio.dto.sql;

import lombok.Data;

/** SQL 风险项对象。 */
@Data
public class RiskItemVO {

    /** 风险规则编码。 */
    private String ruleCode;

    /** 风险描述。 */
    private String description;

    /** 风险等级。 */
    private String level;
}
