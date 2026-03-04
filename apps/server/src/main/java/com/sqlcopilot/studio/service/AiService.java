package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.ai.AiGenerateSqlReq;
import com.sqlcopilot.studio.dto.ai.AiAutoQueryVO;
import com.sqlcopilot.studio.dto.ai.AiGenerateChartVO;
import com.sqlcopilot.studio.dto.ai.AiGenerateSqlVO;
import com.sqlcopilot.studio.dto.ai.AiRepairReq;
import com.sqlcopilot.studio.dto.ai.AiRepairVO;
import com.sqlcopilot.studio.dto.ai.AiTextResponseVO;

public interface AiService {

    AiGenerateSqlVO generateSql(AiGenerateSqlReq req);

    AiAutoQueryVO autoQuery(AiGenerateSqlReq req);

    AiGenerateChartVO generateChart(AiGenerateSqlReq req);

    AiTextResponseVO explainSql(AiGenerateSqlReq req);

    AiTextResponseVO analyzeSql(AiGenerateSqlReq req);

    AiRepairVO repairSql(AiRepairReq req);
}
