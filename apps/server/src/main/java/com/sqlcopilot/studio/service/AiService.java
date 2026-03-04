package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.ai.*;
import com.sqlcopilot.studio.dto.schema.ErAiInferenceReq;
import com.sqlcopilot.studio.dto.schema.ErAiInferenceResultVO;

public interface AiService {

    AiGenerateSqlVO generateSql(AiGenerateSqlReq req);

    AiAutoQueryVO autoQuery(AiGenerateSqlReq req);

    AiGenerateChartVO generateChart(AiGenerateSqlReq req);

    AiTextResponseVO explainSql(AiGenerateSqlReq req);

    AiTextResponseVO analyzeSql(AiGenerateSqlReq req);

    ErAiInferenceResultVO inferErRelations(ErAiInferenceReq req);

    AiRepairVO repairSql(AiRepairReq req);
}
