package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.schema.ErGraphReq;
import com.sqlcopilot.studio.dto.schema.ErGraphVO;

public interface ErDiagramService {

    ErGraphVO buildErGraph(ErGraphReq req);
}

