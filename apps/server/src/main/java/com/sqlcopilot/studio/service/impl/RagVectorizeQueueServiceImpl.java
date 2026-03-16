package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.rag.*;
import com.sqlcopilot.studio.dto.schema.SchemaSyncVO;
import com.sqlcopilot.studio.dto.schema.TableDetailVO;
import com.sqlcopilot.studio.entity.RagVectorizeStatusEntity;
import com.sqlcopilot.studio.entity.SchemaColumnCacheEntity;
import com.sqlcopilot.studio.entity.SchemaTableCacheEntity;
import com.sqlcopilot.studio.mapper.RagVectorizeStatusMapper;
import com.sqlcopilot.studio.service.RagVectorizeQueueService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
import com.sqlcopilot.studio.service.rag.model.QdrantCollectionMetric;
import com.sqlcopilot.studio.service.rag.model.QdrantPayloadFilter;
import com.sqlcopilot.studio.service.rag.model.RagCollectionNames;
import com.sqlcopilot.studio.util.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class RagVectorizeQueueServiceImpl implements RagVectorizeQueueService {

    private static final Logger log = LoggerFactory.getLogger(RagVectorizeQueueServiceImpl.class);
    private static final long GLOBAL_SCOPE_CONNECTION_ID = 0L;

    private final SchemaService schemaService;
    private final RagVectorizeStatusMapper ragVectorizeStatusMapper;
    private final RagIngestionService ragIngestionService;
    private final RagEmbeddingService ragEmbeddingService;
    private final QdrantClientService qdrantClientService;
    private final RagCollectionNames collectionNames;
    private final LinkedBlockingQueue<VectorizeTask> taskQueue = new LinkedBlockingQueue<>();
    private final Set<String> dedupeKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> interruptedKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> suppressedTaskKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, VectorizeStatusRecord> statusMap = new ConcurrentHashMap<>();
    private final Object taskControlLock = new Object();
    private volatile Thread workerThread;
    private volatile VectorizeTask runningTask;
    private volatile boolean shuttingDown;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rag-vectorize-worker");
        thread.setDaemon(true);
        return thread;
    });

    public RagVectorizeQueueServiceImpl(SchemaService schemaService,
                                        RagVectorizeStatusMapper ragVectorizeStatusMapper,
                                        RagIngestionService ragIngestionService,
                                        RagEmbeddingService ragEmbeddingService,
                                        QdrantClientService qdrantClientService,
                                        @Value("${rag.collection.schema-table:schema_table}") String schemaTableCollection,
                                        @Value("${rag.collection.schema-column:schema_column}") String schemaColumnCollection,
                                        @Value("${rag.collection.sql-history:sql_history}") String sqlHistoryCollection,
                                        @Value("${rag.collection.metric-term:metric_term}") String metricTermCollection,
                                        @Value("${rag.collection.example-sql:example_sql}") String exampleSqlCollection,
                                        @Value("${rag.collection.sql-fragment:sql_fragment}") String sqlFragmentCollection) {
        this.schemaService = schemaService;
        this.ragVectorizeStatusMapper = ragVectorizeStatusMapper;
        this.ragIngestionService = ragIngestionService;
        this.ragEmbeddingService = ragEmbeddingService;
        this.qdrantClientService = qdrantClientService;
        this.collectionNames = new RagCollectionNames(
            schemaTableCollection,
            schemaColumnCollection,
            sqlHistoryCollection,
            metricTermCollection,
            exampleSqlCollection,
            sqlFragmentCollection
        );
    }

    @PostConstruct
    public void startWorker() {
        recoverInterruptedStatusesOnStartup();
        worker.submit(this::processLoop);
    }

    @PreDestroy
    public void stopWorker() {
        shuttingDown = true;
        worker.shutdownNow();
    }

    @Override
    public RagVectorizeEnqueueVO enqueue(Long connectionId, String databaseName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        if (connectionId == null) {
            throw new BusinessException(400, "connectionId 不能为空");
        }
        if (normalizedDatabaseName.isBlank()) {
            throw new BusinessException(400, "databaseName 不能为空");
        }
        String statusKey = buildTaskKey(connectionId, normalizedDatabaseName);
        return enqueueTask(
            VectorizeTask.database(connectionId, normalizedDatabaseName, statusKey),
            "已加入向量化队列",
            "已加入向量化队列，任务将按顺序执行",
            "该数据库已在向量化队列中"
        );
    }

    @Override
    public RagVectorizeEnqueueVO enqueueTable(Long connectionId, String databaseName, String tableName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        String normalizedTableName = normalizeTableName(tableName);
        if (connectionId == null) {
            throw new BusinessException(400, "connectionId 不能为空");
        }
        if (normalizedDatabaseName.isBlank()) {
            throw new BusinessException(400, "databaseName 不能为空");
        }
        if (normalizedTableName.isBlank()) {
            throw new BusinessException(400, "tableName 不能为空");
        }
        String statusKey = buildTaskKey(connectionId, normalizedDatabaseName);
        return enqueueTask(
            VectorizeTask.table(connectionId, normalizedDatabaseName, statusKey, normalizedTableName),
            "表 " + normalizedTableName + " 已加入向量化队列",
            "已加入单表向量化队列，任务将按顺序执行",
            "该表已在向量化队列中"
        );
    }

    @Override
    public RagVectorizeTableVO vectorizeTable(Long connectionId, String databaseName, String tableName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        String normalizedTableName = normalizeTableName(tableName);
        if (connectionId == null) {
            throw new BusinessException(400, "connectionId 不能为空");
        }
        if (normalizedDatabaseName.isBlank()) {
            throw new BusinessException(400, "databaseName 不能为空");
        }
        if (normalizedTableName.isBlank()) {
            throw new BusinessException(400, "tableName 不能为空");
        }

        String taskKey = buildTaskKey(connectionId, normalizedDatabaseName);
        VectorizeStatusRecord runtimeStatus = statusMap.get(taskKey);
        if (runtimeStatus != null
            && (VectorizeStatus.PENDING.name().equals(runtimeStatus.status())
            || VectorizeStatus.RUNNING.name().equals(runtimeStatus.status()))) {
            throw new BusinessException(400, "当前数据库正在向量化，请稍后再试");
        }

        vectorizeSingleTable(connectionId, normalizedDatabaseName, normalizedTableName);
        upsertStatus(
            connectionId,
            normalizedDatabaseName,
            taskKey,
            VectorizeStatus.SUCCESS,
            "表 " + normalizedTableName + " 已完成手动向量化"
        );

        RagVectorizeTableVO vo = new RagVectorizeTableVO();
        vo.setSuccess(Boolean.TRUE);
        vo.setDatabaseName(normalizedDatabaseName);
        vo.setTableName(normalizedTableName);
        vo.setMessage("已完成单表手动向量化");
        vo.setUpdatedAt(System.currentTimeMillis());
        return vo;
    }

    @Override
    public RagVectorizeInterruptVO interrupt(Long connectionId, String databaseName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        if (connectionId == null) {
            throw new BusinessException(400, "connectionId 不能为空");
        }
        if (normalizedDatabaseName.isBlank()) {
            throw new BusinessException(400, "databaseName 不能为空");
        }

        String taskKey = buildTaskKey(connectionId, normalizedDatabaseName);
        synchronized (taskControlLock) {
            if (runningTask != null && taskKey.equals(runningTask.statusKey())) {
                // 关键操作：运行中任务通过线程中断信号终止，并立即落库失败状态。
                interruptedKeys.add(runningTask.dedupeKey());
                upsertStatus(connectionId, normalizedDatabaseName, taskKey, VectorizeStatus.FAILED, "向量化任务已中断");
                dedupeKeys.remove(runningTask.dedupeKey());
                Thread workerRef = workerThread;
                if (workerRef != null) {
                    workerRef.interrupt();
                }
                return buildInterruptVo(Boolean.TRUE, VectorizeStatus.FAILED.name(), "已发送中断信号，任务将尽快停止");
            }

            boolean removed = taskQueue.removeIf(task -> taskKey.equals(task.statusKey()));
            if (removed) {
                upsertStatus(connectionId, normalizedDatabaseName, taskKey, VectorizeStatus.FAILED, "向量化任务已中断（已移出队列）");
                dedupeKeys.removeIf(dedupeKey -> isDatabaseScopedDedupeKey(dedupeKey, taskKey));
                interruptedKeys.removeIf(dedupeKey -> isDatabaseScopedDedupeKey(dedupeKey, taskKey));
                return buildInterruptVo(Boolean.TRUE, VectorizeStatus.FAILED.name(), "排队中的向量化任务已中断");
            }
        }

        RagDatabaseVectorizeStatusVO current = getStatus(connectionId, normalizedDatabaseName);
        if (VectorizeStatus.PENDING.name().equals(current.getStatus())
            || VectorizeStatus.RUNNING.name().equals(current.getStatus())) {
            upsertStatus(connectionId, normalizedDatabaseName, taskKey, VectorizeStatus.FAILED, "向量化任务已中断");
            dedupeKeys.removeIf(dedupeKey -> isDatabaseScopedDedupeKey(dedupeKey, taskKey));
            interruptedKeys.removeIf(dedupeKey -> isDatabaseScopedDedupeKey(dedupeKey, taskKey));
            return buildInterruptVo(Boolean.TRUE, VectorizeStatus.FAILED.name(), "向量化任务已中断");
        }
        return buildInterruptVo(Boolean.FALSE, current.getStatus(), "当前数据库没有可中断的向量化任务");
    }

    @Override
    public List<RagDatabaseVectorizeStatusVO> listStatus(Long connectionId) {
        Map<String, RagDatabaseVectorizeStatusVO> merged = new LinkedHashMap<>();
        List<RagVectorizeStatusEntity> persisted = ragVectorizeStatusMapper.findByConnectionId(connectionId);
        for (RagVectorizeStatusEntity item : persisted) {
            if (item.getDatabaseName() == null) {
                continue;
            }
            RagDatabaseVectorizeStatusVO vo = new RagDatabaseVectorizeStatusVO();
            vo.setDatabaseName(item.getDatabaseName());
            vo.setStatus(item.getStatus());
            vo.setMessage(item.getMessage());
            vo.setUpdatedAt(item.getUpdatedAt());
            merged.put(buildTaskKey(connectionId, item.getDatabaseName()), vo);
            statusMap.putIfAbsent(
                buildTaskKey(connectionId, item.getDatabaseName()),
                new VectorizeStatusRecord(
                    connectionId,
                    item.getDatabaseName(),
                    item.getStatus(),
                    item.getMessage(),
                    item.getUpdatedAt(),
                    item.getLastFullVectorizeDurationMs(),
                    item.getLastFullVectorizeProvider()
                )
            );
        }

        statusMap.values().stream()
            .filter(item -> Objects.equals(connectionId, item.connectionId()))
            .map(item -> {
                RagDatabaseVectorizeStatusVO vo = new RagDatabaseVectorizeStatusVO();
                vo.setDatabaseName(item.databaseName());
                vo.setStatus(item.status());
                vo.setMessage(item.message());
                vo.setUpdatedAt(item.updatedAt());
                return vo;
            })
            .forEach(item -> merged.put(buildTaskKey(connectionId, item.getDatabaseName()), item));

        return merged.values().stream()
            .sorted(Comparator.comparing(RagDatabaseVectorizeStatusVO::getDatabaseName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    @Override
    public RagDatabaseVectorizeStatusVO getStatus(Long connectionId, String databaseName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        if (connectionId == null) {
            throw new BusinessException(400, "connectionId 不能为空");
        }
        if (normalizedDatabaseName.isBlank()) {
            throw new BusinessException(400, "databaseName 不能为空");
        }

        String taskKey = buildTaskKey(connectionId, normalizedDatabaseName);
        VectorizeStatusRecord runtime = statusMap.get(taskKey);
        if (runtime != null) {
            return toStatusVo(runtime.databaseName(), runtime.status(), runtime.message(), runtime.updatedAt());
        }

        RagVectorizeStatusEntity persisted = ragVectorizeStatusMapper.findOne(connectionId, normalizedDatabaseName);
        if (persisted != null) {
            VectorizeStatusRecord persistedRecord = new VectorizeStatusRecord(
                connectionId,
                normalizedDatabaseName,
                persisted.getStatus(),
                persisted.getMessage(),
                persisted.getUpdatedAt(),
                persisted.getLastFullVectorizeDurationMs(),
                persisted.getLastFullVectorizeProvider()
            );
            statusMap.putIfAbsent(taskKey, persistedRecord);
            return toStatusVo(
                normalizedDatabaseName,
                persisted.getStatus(),
                persisted.getMessage(),
                persisted.getUpdatedAt()
            );
        }
        return toStatusVo(normalizedDatabaseName, "NOT_VECTORIZED", "暂无向量化数据", null);
    }

    @Override
    public RagVectorizeOverviewVO getOverview(Long connectionId, String databaseName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        if (connectionId == null) {
            throw new BusinessException(400, "connectionId 不能为空");
        }
        if (normalizedDatabaseName.isBlank()) {
            throw new BusinessException(400, "databaseName 不能为空");
        }

        // 关键操作：概要统计直接读取 Qdrant 当前集合数据，确保展示的是最新向量规模与维度。
        QdrantCollectionMetric tableMetric = qdrantClientService.queryCollectionMetric(
            collectionNames.getSchemaTable(),
            connectionId,
            normalizedDatabaseName
        );
        QdrantCollectionMetric columnMetric = qdrantClientService.queryCollectionMetric(
            collectionNames.getSchemaColumn(),
            connectionId,
            normalizedDatabaseName
        );
        QdrantCollectionMetric historyMetric = qdrantClientService.queryCollectionMetric(
            collectionNames.getSqlHistory(),
            connectionId,
            normalizedDatabaseName
        );
        QdrantCollectionMetric fragmentMetric = qdrantClientService.queryCollectionMetric(
            collectionNames.getSqlFragment(),
            connectionId,
            normalizedDatabaseName
        );
        QdrantCollectionMetric metricTermDatabaseMetric = qdrantClientService.queryCollectionMetricByFilters(
            collectionNames.getMetricTerm(),
            List.of(
                new QdrantPayloadFilter("connection_id", connectionId),
                new QdrantPayloadFilter("database_name", normalizedDatabaseName)
            )
        );
        QdrantCollectionMetric exampleSqlDatabaseMetric = qdrantClientService.queryCollectionMetricByFilters(
            collectionNames.getExampleSql(),
            List.of(
                new QdrantPayloadFilter("connection_id", connectionId),
                new QdrantPayloadFilter("database_name", normalizedDatabaseName)
            )
        );
        QdrantCollectionMetric metricTermConnectionMetric = qdrantClientService.queryCollectionMetricByFilters(
            collectionNames.getMetricTerm(),
            List.of(
                new QdrantPayloadFilter("connection_id", connectionId),
                new QdrantPayloadFilter("database_name", "")
            )
        );
        QdrantCollectionMetric exampleSqlConnectionMetric = qdrantClientService.queryCollectionMetricByFilters(
            collectionNames.getExampleSql(),
            List.of(
                new QdrantPayloadFilter("connection_id", connectionId),
                new QdrantPayloadFilter("database_name", "")
            )
        );
        QdrantCollectionMetric metricTermGlobalMetric = qdrantClientService.queryCollectionMetricByFilters(
            collectionNames.getMetricTerm(),
            List.of(
                new QdrantPayloadFilter("connection_id", GLOBAL_SCOPE_CONNECTION_ID),
                new QdrantPayloadFilter("database_name", "")
            )
        );
        QdrantCollectionMetric exampleSqlGlobalMetric = qdrantClientService.queryCollectionMetricByFilters(
            collectionNames.getExampleSql(),
            List.of(
                new QdrantPayloadFilter("connection_id", GLOBAL_SCOPE_CONNECTION_ID),
                new QdrantPayloadFilter("database_name", "")
            )
        );

        long metricTermApplicableCount = mergeKnowledgeApplicableCount(
            metricTermDatabaseMetric,
            metricTermConnectionMetric,
            metricTermGlobalMetric
        );
        long exampleSqlApplicableCount = mergeKnowledgeApplicableCount(
            exampleSqlDatabaseMetric,
            exampleSqlConnectionMetric,
            exampleSqlGlobalMetric
        );

        long totalCount = safeCount(tableMetric.getPointCount())
            + safeCount(columnMetric.getPointCount())
            + safeCount(historyMetric.getPointCount())
            + safeCount(fragmentMetric.getPointCount())
            + metricTermApplicableCount
            + exampleSqlApplicableCount;

        String taskKey = buildTaskKey(connectionId, normalizedDatabaseName);
        VectorizeStatusRecord statusRecord = statusMap.get(taskKey);
        RagVectorizeStatusEntity persistedStatus = ragVectorizeStatusMapper.findOne(connectionId, normalizedDatabaseName);
        if (statusRecord == null && persistedStatus != null) {
            statusRecord = new VectorizeStatusRecord(
                connectionId,
                normalizedDatabaseName,
                persistedStatus.getStatus(),
                persistedStatus.getMessage(),
                persistedStatus.getUpdatedAt(),
                persistedStatus.getLastFullVectorizeDurationMs(),
                persistedStatus.getLastFullVectorizeProvider()
            );
            statusMap.putIfAbsent(taskKey, statusRecord);
        }

        RagVectorizeOverviewVO vo = new RagVectorizeOverviewVO();
        vo.setDatabaseName(normalizedDatabaseName);
        vo.setStatus(resolveOverviewStatus(statusRecord, totalCount));
        vo.setMessage(resolveOverviewMessage(statusRecord, totalCount));
        vo.setUpdatedAt(statusRecord == null ? null : statusRecord.updatedAt());
        vo.setTotalVectorCount(totalCount);
        vo.setSchemaTableVectorCount(safeCount(tableMetric.getPointCount()));
        vo.setSchemaColumnVectorCount(safeCount(columnMetric.getPointCount()));
        vo.setSqlHistoryVectorCount(safeCount(historyMetric.getPointCount()));
        vo.setSqlFragmentVectorCount(safeCount(fragmentMetric.getPointCount()));
        vo.setMetricTermVectorCount(metricTermApplicableCount);
        vo.setExampleSqlVectorCount(exampleSqlApplicableCount);
        vo.setGlobalMetricTermVectorCount(safeCount(metricTermGlobalMetric.getPointCount()));
        vo.setGlobalExampleSqlVectorCount(safeCount(exampleSqlGlobalMetric.getPointCount()));
        vo.setGlobalVectorCount(
            safeCount(metricTermGlobalMetric.getPointCount()) + safeCount(exampleSqlGlobalMetric.getPointCount())
        );
        vo.setVectorDimension(resolveVectorDimension(
            tableMetric,
            columnMetric,
            historyMetric,
            fragmentMetric,
            metricTermConnectionMetric,
            exampleSqlConnectionMetric,
            metricTermGlobalMetric,
            exampleSqlGlobalMetric
        ));
        vo.setLastFullVectorizeDurationMs(statusRecord == null ? null : statusRecord.lastFullVectorizeDurationMs());
        vo.setLastFullVectorizeProvider(statusRecord == null ? null : statusRecord.lastFullVectorizeProvider());
        return vo;
    }

    @Override
    public void clearDatabaseState(Long connectionId, String databaseName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        if (connectionId == null || normalizedDatabaseName.isBlank()) {
            return;
        }
        String taskKey = buildTaskKey(connectionId, normalizedDatabaseName);
        suppressMatchingTasks(task -> taskKey.equals(task.statusKey()));
        dedupeKeys.removeIf(dedupeKey -> isDatabaseScopedDedupeKey(dedupeKey, taskKey));
        interruptedKeys.removeIf(dedupeKey -> isDatabaseScopedDedupeKey(dedupeKey, taskKey));
        statusMap.remove(taskKey);
        ragVectorizeStatusMapper.deleteOne(connectionId, normalizedDatabaseName);
    }

    @Override
    public void clearConnectionState(Long connectionId) {
        if (connectionId == null) {
            return;
        }
        suppressMatchingTasks(task -> Objects.equals(connectionId, task.connectionId()));
        dedupeKeys.removeIf(key -> key.startsWith(connectionId + "|"));
        interruptedKeys.removeIf(key -> key.startsWith(connectionId + "|"));
        statusMap.entrySet().removeIf(entry -> Objects.equals(connectionId, entry.getValue().connectionId()));
        ragVectorizeStatusMapper.deleteByConnectionId(connectionId);
    }

    private void processLoop() {
        workerThread = Thread.currentThread();
        while (!Thread.currentThread().isInterrupted()) {
            VectorizeTask task = null;
            boolean interruptedByRequest = false;
            try {
                task = taskQueue.take();
                synchronized (taskControlLock) {
                    runningTask = task;
                }
                upsertStatus(task.connectionId(), task.databaseName(), task.statusKey(), VectorizeStatus.RUNNING,
                    task.scope() == VectorizeTaskScope.TABLE
                        ? "单表向量化执行中: " + Objects.toString(task.tableName(), "")
                        : "向量化执行中");
                log.info("开始执行{}向量化任务, connectionId={}, databaseName={}, tableName={}",
                    task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                    task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""));
                long startAt = System.currentTimeMillis();
                executeQueuedTask(task);
                if (suppressedTaskKeys.contains(task.dedupeKey())) {
                    log.info("{}向量化任务结果已忽略, connectionId={}, databaseName={}, tableName={}",
                        task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                        task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""));
                    continue;
                }
                if (interruptedKeys.remove(task.dedupeKey())) {
                    interruptedByRequest = true;
                    log.info("{}向量化任务已中断, connectionId={}, databaseName={}, tableName={}",
                        task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                        task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""));
                } else {
                    upsertStatus(
                        task.connectionId(),
                        task.databaseName(),
                        task.statusKey(),
                        VectorizeStatus.SUCCESS,
                        task.scope() == VectorizeTaskScope.TABLE
                            ? "表 " + task.tableName() + " 向量化完成"
                            : "向量化完成",
                        task.scope() == VectorizeTaskScope.DATABASE
                            ? Long.valueOf(Math.max(0L, System.currentTimeMillis() - startAt))
                            : null,
                        task.scope() == VectorizeTaskScope.DATABASE ? resolveRuntimeProviderSafely() : null
                    );
                    log.info("{}向量化任务完成, connectionId={}, databaseName={}, tableName={}, durationMs={}",
                        task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                        task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""),
                        Math.max(0L, System.currentTimeMillis() - startAt));
                }
            } catch (InterruptedException ex) {
                if (task != null && suppressedTaskKeys.contains(task.dedupeKey())) {
                    log.info("{}向量化任务已因元数据变更取消, connectionId={}, databaseName={}, tableName={}",
                        task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                        task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""));
                    interruptedByRequest = true;
                }
                if (task != null && interruptedKeys.remove(task.dedupeKey())) {
                    interruptedByRequest = true;
                    log.info("{}向量化任务中断完成, connectionId={}, databaseName={}, tableName={}",
                        task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                        task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""));
                }
                if (shuttingDown) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception ex) {
                if (task != null) {
                    if (suppressedTaskKeys.contains(task.dedupeKey())) {
                        log.info("{}向量化任务异常结果已忽略, connectionId={}, databaseName={}, tableName={}, reason={}",
                            task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                            task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""), ex.getMessage());
                    } else
                    if (interruptedKeys.remove(task.dedupeKey())) {
                        interruptedByRequest = true;
                        log.info("{}向量化任务已中断, connectionId={}, databaseName={}, tableName={}",
                            task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                            task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""));
                    } else {
                        upsertStatus(task.connectionId(), task.databaseName(), task.statusKey(), VectorizeStatus.FAILED,
                            truncateError(ex.getMessage()));
                        log.warn("{}向量化任务失败, connectionId={}, databaseName={}, tableName={}, reason={}",
                            task.scope() == VectorizeTaskScope.TABLE ? "单表" : "数据库",
                            task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""), ex.getMessage());
                    }
                } else {
                    log.warn("向量化任务失败, reason={}", ex.getMessage());
                }
            } finally {
                if (task != null) {
                    dedupeKeys.remove(task.dedupeKey());
                    suppressedTaskKeys.remove(task.dedupeKey());
                    synchronized (taskControlLock) {
                        if (runningTask != null && task.dedupeKey().equals(runningTask.dedupeKey())) {
                            runningTask = null;
                        }
                    }
                }
                if (interruptedByRequest && !shuttingDown) {
                    // 关键操作：仅清理“手动中断任务”产生的中断标记，避免 worker 线程被误停。
                    Thread.interrupted();
                }
            }
        }
    }

    private void recoverInterruptedStatusesOnStartup() {
        List<RagVectorizeStatusEntity> inProgress = ragVectorizeStatusMapper.findInProgress();
        if (inProgress.isEmpty()) {
            return;
        }
        for (RagVectorizeStatusEntity item : inProgress) {
            if (item.getConnectionId() == null) {
                continue;
            }
            String normalizedDatabaseName = normalizeDatabaseName(item.getDatabaseName());
            if (normalizedDatabaseName.isBlank()) {
                continue;
            }
            String taskKey = buildTaskKey(item.getConnectionId(), normalizedDatabaseName);
            // 关键操作：服务重启后将历史执行中/排队中任务改为失败，避免前端状态卡死。
            upsertStatus(item.getConnectionId(), normalizedDatabaseName, taskKey, VectorizeStatus.FAILED,
                "任务因服务重启中断，请重新向量化");
            dedupeKeys.removeIf(dedupeKey -> isDatabaseScopedDedupeKey(dedupeKey, taskKey));
            interruptedKeys.removeIf(dedupeKey -> isDatabaseScopedDedupeKey(dedupeKey, taskKey));
        }
    }

    private String buildTaskKey(Long connectionId, String databaseName) {
        return connectionId + "|" + databaseName.toLowerCase();
    }

    private static String buildTableTaskDedupeKey(String statusKey, String tableName) {
        return statusKey + "|table|" + tableName.toLowerCase(Locale.ROOT);
    }

    private void suppressMatchingTasks(java.util.function.Predicate<VectorizeTask> predicate) {
        if (predicate == null) {
            return;
        }
        synchronized (taskControlLock) {
            taskQueue.removeIf(predicate::test);
            if (runningTask != null && predicate.test(runningTask)) {
                suppressedTaskKeys.add(runningTask.dedupeKey());
                interruptedKeys.add(runningTask.dedupeKey());
                Thread workerRef = workerThread;
                if (workerRef != null) {
                    workerRef.interrupt();
                }
            }
        }
    }

    private RagVectorizeEnqueueVO enqueueTask(VectorizeTask task,
                                              String pendingMessage,
                                              String successMessage,
                                              String duplicatePrefix) {
        RagVectorizeEnqueueVO vo = new RagVectorizeEnqueueVO();
        boolean accepted = dedupeKeys.add(task.dedupeKey());
        if (!accepted) {
            VectorizeStatusRecord existing = statusMap.get(task.statusKey());
            vo.setEnqueued(Boolean.FALSE);
            vo.setQueueSize(dedupeKeys.size());
            vo.setMessage(existing != null && VectorizeStatus.PENDING.name().equals(existing.status())
                ? duplicatePrefix + "（排队中），请勿重复提交"
                : duplicatePrefix + "（执行中），请勿重复提交");
            return vo;
        }
        upsertStatus(task.connectionId(), task.databaseName(), task.statusKey(), VectorizeStatus.PENDING, pendingMessage);
        taskQueue.offer(task);
        vo.setEnqueued(Boolean.TRUE);
        vo.setQueueSize(dedupeKeys.size());
        vo.setMessage(successMessage);
        return vo;
    }

    private void executeQueuedTask(VectorizeTask task) {
        if (task.scope() == VectorizeTaskScope.TABLE) {
            vectorizeSingleTable(task.connectionId(), task.databaseName(), Objects.toString(task.tableName(), ""));
            return;
        }
        // 关键操作：执行整库队列任务时直接触发 Schema 同步，复用现有元数据读取与向量化链路。
        SchemaSyncVO syncResult = schemaService.syncSchema(task.connectionId(), task.databaseName());
        ensureDatabaseVectorsPresent(
            task.connectionId(),
            task.databaseName(),
            syncResult == null ? null : syncResult.getTableCount(),
            syncResult == null ? null : syncResult.getColumnCount()
        );
    }

    private void vectorizeSingleTable(Long connectionId, String databaseName, String tableName) {
        schemaService.refreshSchemaCache(connectionId, databaseName);
        TableDetailVO detail = schemaService.getTableDetail(connectionId, databaseName, tableName);
        if (detail == null || detail.getColumns() == null || detail.getColumns().isEmpty()) {
            throw new BusinessException(400, "未读取到目标表字段信息，无法向量化");
        }

        long now = System.currentTimeMillis();
        SchemaTableCacheEntity tableMeta = new SchemaTableCacheEntity();
        tableMeta.setConnectionId(connectionId);
        tableMeta.setDatabaseName(databaseName);
        tableMeta.setTableName(tableName);
        tableMeta.setUpdatedAt(now);
        if (tableMeta.getRowEstimate() == null) {
            tableMeta.setRowEstimate(0L);
        }
        if (tableMeta.getTableSizeBytes() == null) {
            tableMeta.setTableSizeBytes(0L);
        }

        List<SchemaColumnCacheEntity> columnMetaList = detail.getColumns().stream().map(column -> {
            SchemaColumnCacheEntity entity = new SchemaColumnCacheEntity();
            entity.setConnectionId(connectionId);
            entity.setDatabaseName(databaseName);
            entity.setTableName(tableName);
            entity.setColumnName(Objects.toString(column.getColumnName(), "").trim());
            entity.setDataType(column.getDataType());
            entity.setColumnSize(column.getColumnSize());
            entity.setDecimalDigits(column.getDecimalDigits());
            entity.setColumnDefault(column.getDefaultValue());
            entity.setAutoIncrementFlag(toIntFlag(column.getAutoIncrement()));
            entity.setNullableFlag(toIntFlag(column.getNullable()));
            entity.setColumnComment(column.getColumnComment());
            entity.setIndexedFlag(toIntFlag(column.getIndexed()));
            entity.setPrimaryKeyFlag(toIntFlag(column.getPrimaryKey()));
            entity.setUpdatedAt(now);
            return entity;
        }).filter(item -> !Objects.toString(item.getColumnName(), "").isBlank()).toList();
        if (columnMetaList.isEmpty()) {
            throw new BusinessException(400, "未读取到目标表字段信息，无法向量化");
        }

        ragIngestionService.ingestSchema(
            connectionId,
            databaseName,
            List.of(tableMeta),
            columnMetaList
        );
        ensureTableVectorsPresent(connectionId, databaseName, tableName);
    }

    private boolean isDatabaseScopedDedupeKey(String dedupeKey, String statusKey) {
        return Objects.toString(dedupeKey, "").equals(statusKey)
            || Objects.toString(dedupeKey, "").startsWith(statusKey + "|table|");
    }

    private String normalizeDatabaseName(String databaseName) {
        return Objects.toString(databaseName, "").trim();
    }

    private String normalizeTableName(String tableName) {
        return Objects.toString(tableName, "")
            .replace("`", "")
            .replace("\"", "")
            .trim();
    }

    private Integer toIntFlag(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private long safeCount(Long count) {
        return count == null ? 0L : Math.max(0L, count);
    }

    private long mergeKnowledgeApplicableCount(QdrantCollectionMetric databaseMetric,
                                               QdrantCollectionMetric connectionMetric,
                                               QdrantCollectionMetric globalMetric) {
        long databaseCount = safeCount(databaseMetric == null ? null : databaseMetric.getPointCount());
        long connectionOnlyCount = safeCount(connectionMetric == null ? null : connectionMetric.getPointCount());
        long globalCount = safeCount(globalMetric == null ? null : globalMetric.getPointCount());
        return databaseCount + connectionOnlyCount + globalCount;
    }

    private String resolveOverviewStatus(VectorizeStatusRecord statusRecord, long totalCount) {
        if (statusRecord != null) {
            return statusRecord.status();
        }
        return totalCount > 0 ? VectorizeStatus.SUCCESS.name() : "NOT_VECTORIZED";
    }

    private String resolveOverviewMessage(VectorizeStatusRecord statusRecord, long totalCount) {
        if (statusRecord != null) {
            if (totalCount <= 0 && VectorizeStatus.SUCCESS.name().equals(statusRecord.status())) {
                return "当前库暂无向量化数据";
            }
            return statusRecord.message();
        }
        return totalCount > 0 ? "已向量化（历史统计）" : "当前库暂无向量化数据";
    }

    private Integer resolveVectorDimension(QdrantCollectionMetric... metrics) {
        for (QdrantCollectionMetric metric : metrics) {
            if (metric == null || metric.getVectorDimension() == null) {
                continue;
            }
            int value = metric.getVectorDimension();
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private void ensureDatabaseVectorsPresent(Long connectionId,
                                              String databaseName,
                                              Integer tableCount,
                                              Integer columnCount) {
        long expectedTableCount = tableCount == null ? 0L : Math.max(0, tableCount);
        long expectedColumnCount = columnCount == null ? 0L : Math.max(0, columnCount);
        if (expectedTableCount <= 0 && expectedColumnCount <= 0) {
            return;
        }

        long actualCount = safeCount(qdrantClientService.queryCollectionMetric(
            collectionNames.getSchemaTable(),
            connectionId,
            databaseName
        ).getPointCount()) + safeCount(qdrantClientService.queryCollectionMetric(
            collectionNames.getSchemaColumn(),
            connectionId,
            databaseName
        ).getPointCount());
        if (actualCount <= 0) {
            throw new BusinessException(500, "向量化完成后未检测到写入数据，请检查向量模型和 Qdrant 状态");
        }
    }

    private void ensureTableVectorsPresent(Long connectionId, String databaseName, String tableName) {
        List<QdrantPayloadFilter> filters = List.of(
            new QdrantPayloadFilter("connection_id", connectionId),
            new QdrantPayloadFilter("database_name", databaseName),
            new QdrantPayloadFilter("table_name", tableName)
        );
        long actualCount = safeCount(qdrantClientService.queryCollectionMetricByFilters(
            collectionNames.getSchemaTable(),
            filters
        ).getPointCount()) + safeCount(qdrantClientService.queryCollectionMetricByFilters(
            collectionNames.getSchemaColumn(),
            filters
        ).getPointCount());
        if (actualCount <= 0) {
            throw new BusinessException(500, "表向量化完成后未检测到写入数据，请检查向量模型和 Qdrant 状态");
        }
    }

    private String resolveRuntimeProviderSafely() {
        try {
            String normalized = normalizeRuntimeProvider(ragEmbeddingService.getRuntimeProvider());
            return normalized.isBlank() ? "UNKNOWN" : normalized;
        } catch (Exception | LinkageError ex) {
            log.warn("读取向量执行引擎失败，使用 UNKNOWN。reason={}", ex.getMessage());
            return "UNKNOWN";
        }
    }

    private String normalizeRuntimeProvider(String provider) {
        return Objects.toString(provider, "")
            .trim()
            .toUpperCase(Locale.ROOT)
            .replace(' ', '_')
            .replace('-', '_');
    }

    private void upsertStatus(Long connectionId, String databaseName, String taskKey, VectorizeStatus status, String message) {
        upsertStatus(connectionId, databaseName, taskKey, status, message, null, null);
    }

    private void upsertStatus(Long connectionId,
                              String databaseName,
                              String taskKey,
                              VectorizeStatus status,
                              String message,
                              Long lastFullVectorizeDurationMs,
                              String lastFullVectorizeProvider) {
        Long updatedAt = System.currentTimeMillis();
        VectorizeStatusRecord previous = statusMap.get(taskKey);
        RagVectorizeStatusEntity persisted = previous == null
            ? ragVectorizeStatusMapper.findOne(connectionId, databaseName)
            : null;

        Long baseDurationMs = previous != null
            ? previous.lastFullVectorizeDurationMs()
            : (persisted == null ? null : persisted.getLastFullVectorizeDurationMs());
        String baseProvider = previous != null
            ? previous.lastFullVectorizeProvider()
            : normalizeRuntimeProvider(persisted == null ? null : persisted.getLastFullVectorizeProvider());

        Long resolvedDurationMs;
        if (lastFullVectorizeDurationMs == null) {
            resolvedDurationMs = baseDurationMs;
        } else {
            resolvedDurationMs = Long.valueOf(Math.max(0L, lastFullVectorizeDurationMs));
        }
        String normalizedProvider = normalizeRuntimeProvider(lastFullVectorizeProvider);
        String resolvedProvider = normalizedProvider.isBlank()
            ? baseProvider
            : normalizedProvider;

        VectorizeStatusRecord record = new VectorizeStatusRecord(
            connectionId,
            databaseName,
            status.name(),
            message,
            updatedAt,
            resolvedDurationMs,
            resolvedProvider
        );
        statusMap.put(taskKey, record);

        RagVectorizeStatusEntity entity = new RagVectorizeStatusEntity();
        entity.setConnectionId(connectionId);
        entity.setDatabaseName(databaseName);
        entity.setStatus(status.name());
        entity.setMessage(message);
        entity.setUpdatedAt(updatedAt);
        entity.setLastFullVectorizeDurationMs(lastFullVectorizeDurationMs == null ? null : Math.max(0L, lastFullVectorizeDurationMs));
        entity.setLastFullVectorizeProvider(normalizedProvider.isBlank() ? null : normalizedProvider);
        ragVectorizeStatusMapper.upsert(entity);
    }

    private RagDatabaseVectorizeStatusVO toStatusVo(String databaseName, String status, String message, Long updatedAt) {
        RagDatabaseVectorizeStatusVO vo = new RagDatabaseVectorizeStatusVO();
        vo.setDatabaseName(databaseName);
        vo.setStatus(status);
        vo.setMessage(message);
        vo.setUpdatedAt(updatedAt);
        return vo;
    }

    private RagVectorizeInterruptVO buildInterruptVo(Boolean interrupted, String status, String message) {
        RagVectorizeInterruptVO vo = new RagVectorizeInterruptVO();
        vo.setInterrupted(interrupted);
        vo.setStatus(status);
        vo.setMessage(message);
        vo.setUpdatedAt(System.currentTimeMillis());
        return vo;
    }

    private String truncateError(String reason) {
        String text = Objects.toString(reason, "").trim();
        if (text.isEmpty()) {
            return "向量化失败，请检查服务日志";
        }
        if (text.length() <= 180) {
            return text;
        }
        return text.substring(0, 180) + "...";
    }

    private record VectorizeTask(Long connectionId,
                                 String databaseName,
                                 String statusKey,
                                 String dedupeKey,
                                 VectorizeTaskScope scope,
                                 String tableName) {

        private static VectorizeTask database(Long connectionId, String databaseName, String statusKey) {
            return new VectorizeTask(connectionId, databaseName, statusKey, statusKey, VectorizeTaskScope.DATABASE, null);
        }

        private static VectorizeTask table(Long connectionId, String databaseName, String statusKey, String tableName) {
            return new VectorizeTask(
                connectionId,
                databaseName,
                statusKey,
                buildTableTaskDedupeKey(statusKey, tableName),
                VectorizeTaskScope.TABLE,
                tableName
            );
        }
    }

    private record VectorizeStatusRecord(Long connectionId, String databaseName, String status,
                                         String message, Long updatedAt,
                                         Long lastFullVectorizeDurationMs,
                                         String lastFullVectorizeProvider) {
    }

    private enum VectorizeStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }

    private enum VectorizeTaskScope {
        DATABASE,
        TABLE
    }
}
