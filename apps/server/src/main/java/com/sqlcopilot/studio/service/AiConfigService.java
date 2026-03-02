package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.ai.AiConfigSaveReq;
import com.sqlcopilot.studio.dto.ai.AiConfigVO;

public interface AiConfigService {

    AiConfigVO getConfig();

    AiConfigVO saveConfig(AiConfigSaveReq req);
}
