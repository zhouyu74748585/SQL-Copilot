package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.memory.*;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;

import java.util.List;

public interface MemoryService {

    MemoryEntryPageVO pageEntries(MemoryEntryPageReq req);

    MemoryEntryVO saveEntry(MemoryEntrySaveReq req);

    void removeEntry(MemoryEntryRemoveReq req);

    MemoryHistoryPageVO pageHistories(MemoryHistoryPageReq req);

    void removeHistory(MemoryHistoryRemoveReq req);

    MemoryEntryVO promoteHistory(MemoryHistoryPromoteReq req);

    void autoUpsertSessionMemory(Long connectionId,
                                 String requestedDatabaseName,
                                 String sessionId,
                                 String summary,
                                 List<QueryHistoryEntity> sourceRows);

    void markRetrieved(List<Long> memoryIds);

    void cleanupLegacyVectors();

    void removeDatabaseArtifacts(Long connectionId, String databaseName);

    void removeConnectionArtifacts(Long connectionId);
}
