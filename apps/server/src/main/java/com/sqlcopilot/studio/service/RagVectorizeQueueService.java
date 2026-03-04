package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.rag.*;

import java.util.List;

public interface RagVectorizeQueueService {

    RagVectorizeEnqueueVO enqueue(Long connectionId, String databaseName);

    RagVectorizeTableVO vectorizeTable(Long connectionId, String databaseName, String tableName);

    RagVectorizeInterruptVO interrupt(Long connectionId, String databaseName);

    List<RagDatabaseVectorizeStatusVO> listStatus(Long connectionId);

    RagDatabaseVectorizeStatusVO getStatus(Long connectionId, String databaseName);

    RagVectorizeOverviewVO getOverview(Long connectionId, String databaseName);
}
