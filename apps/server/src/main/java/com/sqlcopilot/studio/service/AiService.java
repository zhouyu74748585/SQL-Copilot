package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.ai.*;
import com.sqlcopilot.studio.dto.schema.ErAiInferenceReq;
import com.sqlcopilot.studio.dto.schema.ErAiInferenceResultVO;
import com.sqlcopilot.studio.service.stream.AiStreamObserver;

public interface AiService {

    AiGenerateSqlVO generateSql(AiGenerateSqlReq req);

    AiGenerateSqlVO generateSql(AiGenerateSqlReq req, AiStreamObserver observer);

    AiAutoQueryVO autoQuery(AiGenerateSqlReq req);

    AiAutoQueryVO autoQuery(AiGenerateSqlReq req, AiStreamObserver observer);

    AiGenerateChartVO generateChart(AiGenerateSqlReq req);

    AiGenerateChartVO generateChart(AiGenerateSqlReq req, AiStreamObserver observer);

    AiTextResponseVO explainSql(AiGenerateSqlReq req);

    AiTextResponseVO explainSql(AiGenerateSqlReq req, AiStreamObserver observer);

    AiTextResponseVO analyzeSql(AiGenerateSqlReq req);

    AiTextResponseVO analyzeSql(AiGenerateSqlReq req, AiStreamObserver observer);

    ErAiInferenceResultVO inferErRelations(ErAiInferenceReq req);

    AiRepairVO repairSql(AiRepairReq req);

    AiRepairVO repairSql(AiRepairReq req, AiStreamObserver observer);
}
