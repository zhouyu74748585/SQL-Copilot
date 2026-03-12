package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.service.MetadataChangeSyncService;
import com.sqlcopilot.studio.service.RagVectorizeQueueService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class MetadataChangeSyncServiceImpl implements MetadataChangeSyncService {

    private static final Logger log = LoggerFactory.getLogger(MetadataChangeSyncServiceImpl.class);

    private final SchemaService schemaService;
    private final RagVectorizeQueueService ragVectorizeQueueService;
    private final RagIngestionService ragIngestionService;

    public MetadataChangeSyncServiceImpl(SchemaService schemaService,
                                         RagVectorizeQueueService ragVectorizeQueueService,
                                         RagIngestionService ragIngestionService) {
        this.schemaService = schemaService;
        this.ragVectorizeQueueService = ragVectorizeQueueService;
        this.ragIngestionService = ragIngestionService;
    }

    @Override
    public void onConnectionRemoved(Long connectionId) {
        if (connectionId == null) {
            return;
        }
        runSafely("连接级Schema缓存清理", () -> schemaService.refreshConnectionSchemaCaches(connectionId));
        runSafely("连接级向量状态清理", () -> ragVectorizeQueueService.clearConnectionState(connectionId));
        runSafely("连接级向量数据清理", () -> ragIngestionService.removeConnectionArtifacts(connectionId));
    }

    @Override
    public void onDatabaseCreated(Long connectionId, String databaseName) {
        refreshDatabaseCache(connectionId, databaseName);
        revectorizeDatabase(connectionId, databaseName);
    }

    @Override
    public void onDatabaseRenamed(Long connectionId, String sourceDatabaseName, String targetDatabaseName) {
        refreshDatabaseCache(connectionId, sourceDatabaseName);
        refreshDatabaseCache(connectionId, targetDatabaseName);
        runSafely("旧库向量状态清理",
            () -> ragVectorizeQueueService.clearDatabaseState(connectionId, sourceDatabaseName));
        runSafely("旧库向量数据清理",
            () -> ragIngestionService.removeDatabaseArtifacts(connectionId, sourceDatabaseName));
        revectorizeDatabase(connectionId, targetDatabaseName);
    }

    @Override
    public void onDatabaseDropped(Long connectionId, String databaseName) {
        refreshDatabaseCache(connectionId, databaseName);
        runSafely("库向量状态清理",
            () -> ragVectorizeQueueService.clearDatabaseState(connectionId, databaseName));
        runSafely("库向量数据清理",
            () -> ragIngestionService.removeDatabaseArtifacts(connectionId, databaseName));
    }

    @Override
    public void onTableCreatedOrAltered(Long connectionId, String databaseName, String tableName) {
        refreshDatabaseCache(connectionId, databaseName);
        runSafely("单表向量化",
            () -> ragVectorizeQueueService.vectorizeTable(connectionId, databaseName, tableName));
    }

    @Override
    public void onTableRenamed(Long connectionId, String databaseName, String sourceTableName, String targetTableName) {
        refreshDatabaseCache(connectionId, databaseName);
        runSafely("旧表向量数据清理",
            () -> ragIngestionService.removeTableArtifacts(connectionId, databaseName, sourceTableName));
        runSafely("新表单表向量化",
            () -> ragVectorizeQueueService.vectorizeTable(connectionId, databaseName, targetTableName));
    }

    @Override
    public void onTableDropped(Long connectionId, String databaseName, String tableName) {
        refreshDatabaseCache(connectionId, databaseName);
        runSafely("表向量数据清理",
            () -> ragIngestionService.removeTableArtifacts(connectionId, databaseName, tableName));
    }

    @Override
    public void onDatabaseMetadataChanged(Long connectionId, String databaseName) {
        refreshDatabaseCache(connectionId, databaseName);
        revectorizeDatabase(connectionId, databaseName);
    }

    private void refreshDatabaseCache(Long connectionId, String databaseName) {
        runSafely("数据库Schema缓存刷新",
            () -> schemaService.refreshSchemaCache(connectionId, databaseName));
    }

    private void revectorizeDatabase(Long connectionId, String databaseName) {
        runSafely("数据库重新向量化",
            () -> ragVectorizeQueueService.enqueue(connectionId, databaseName));
    }

    private void runSafely(String action, Runnable runnable) {
        if (runnable == null) {
            return;
        }
        try {
            runnable.run();
        } catch (Exception ex) {
            log.warn("{}失败, reason={}", action, Objects.toString(ex.getMessage(), ""));
        }
    }
}
