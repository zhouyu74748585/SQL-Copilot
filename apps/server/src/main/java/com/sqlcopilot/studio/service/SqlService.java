package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.sql.*;

public interface SqlService {

    ExplainVO explain(ExplainReq req);

    RiskEvaluateVO evaluateRisk(RiskEvaluateReq req);

    SqlExecuteVO execute(SqlExecuteReq req);
}
