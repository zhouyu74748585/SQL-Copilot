package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.rag.RagConfigSaveReq;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;

public interface RagConfigService {

    RagConfigVO getConfig();

    RagConfigVO saveConfig(RagConfigSaveReq req);
}
