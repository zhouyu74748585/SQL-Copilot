package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.rag.RagDatabaseVectorizeStatusVO;
import com.sqlcopilot.studio.dto.rag.RagVectorizeEnqueueVO;
import java.util.List;

public interface RagVectorizeQueueService {

    RagVectorizeEnqueueVO enqueue(Long connectionId, String databaseName);

    List<RagDatabaseVectorizeStatusVO> listStatus(Long connectionId);
}
