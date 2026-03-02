package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.ai.AiGenerateSqlReq;
import com.sqlcopilot.studio.dto.ai.AiGenerateSqlVO;
import com.sqlcopilot.studio.dto.ai.AiRepairReq;
import com.sqlcopilot.studio.dto.ai.AiRepairVO;

public interface AiService {

    AiGenerateSqlVO generateSql(AiGenerateSqlReq req);

    AiRepairVO repairSql(AiRepairReq req);
}
