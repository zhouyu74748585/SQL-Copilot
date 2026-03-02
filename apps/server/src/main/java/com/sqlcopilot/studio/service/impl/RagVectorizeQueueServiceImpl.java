package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.rag.RagDatabaseVectorizeStatusVO;
import com.sqlcopilot.studio.dto.rag.RagVectorizeEnqueueVO;
import com.sqlcopilot.studio.service.RagVectorizeQueueService;
import com.sqlcopilot.studio.service.SchemaService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RagVectorizeQueueServiceImpl implements RagVectorizeQueueService {

    private static final Logger log = LoggerFactory.getLogger(RagVectorizeQueueServiceImpl.class);

    private final SchemaService schemaService;
    private final LinkedBlockingQueue<VectorizeTask> taskQueue = new LinkedBlockingQueue<>();
    private final Set<String> dedupeKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, VectorizeStatusRecord> statusMap = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rag-vectorize-worker");
        thread.setDaemon(true);
        return thread;
    });

    public RagVectorizeQueueServiceImpl(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @PostConstruct
    public void startWorker() {
        worker.submit(this::processLoop);
    }

    @PreDestroy
    public void stopWorker() {
        worker.shutdownNow();
    }

    @Override
    public RagVectorizeEnqueueVO enqueue(Long connectionId, String databaseName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        String taskKey = buildTaskKey(connectionId, normalizedDatabaseName);
        RagVectorizeEnqueueVO vo = new RagVectorizeEnqueueVO();

        // 关键操作：同库去重键贯穿“排队+执行”生命周期，禁止重复入队。
        boolean accepted = dedupeKeys.add(taskKey);
        if (!accepted) {
            VectorizeStatusRecord existing = statusMap.get(taskKey);
            vo.setEnqueued(Boolean.FALSE);
            vo.setQueueSize(dedupeKeys.size());
            vo.setMessage(existing != null && VectorizeStatus.PENDING.name().equals(existing.status())
                ? "该数据库已在向量化队列中（排队中），请勿重复提交"
                : "该数据库已在向量化队列中（执行中），请勿重复提交");
            return vo;
        }

        upsertStatus(connectionId, normalizedDatabaseName, taskKey, VectorizeStatus.PENDING, "已加入向量化队列");
        taskQueue.offer(new VectorizeTask(connectionId, normalizedDatabaseName, taskKey));
        vo.setEnqueued(Boolean.TRUE);
        vo.setQueueSize(dedupeKeys.size());
        vo.setMessage("已加入向量化队列，任务将按顺序执行");
        return vo;
    }

    @Override
    public List<RagDatabaseVectorizeStatusVO> listStatus(Long connectionId) {
        return statusMap.values().stream()
            .filter(item -> Objects.equals(connectionId, item.connectionId()))
            .map(item -> {
                RagDatabaseVectorizeStatusVO vo = new RagDatabaseVectorizeStatusVO();
                vo.setDatabaseName(item.databaseName());
                vo.setStatus(item.status());
                vo.setMessage(item.message());
                vo.setUpdatedAt(item.updatedAt());
                return vo;
            })
            .sorted(Comparator.comparing(RagDatabaseVectorizeStatusVO::getDatabaseName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private void processLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            VectorizeTask task = null;
            try {
                task = taskQueue.take();
                upsertStatus(task.connectionId(), task.databaseName(), task.taskKey(), VectorizeStatus.RUNNING, "向量化执行中");
                log.info("开始执行数据库重新向量化任务, connectionId={}, databaseName={}",
                    task.connectionId(), task.databaseName());
                // 关键操作：执行队列任务时直接触发 Schema 同步，复用现有元数据读取与向量化链路。
                schemaService.syncSchema(task.connectionId(), task.databaseName());
                upsertStatus(task.connectionId(), task.databaseName(), task.taskKey(), VectorizeStatus.SUCCESS, "向量化完成");
                log.info("数据库重新向量化任务完成, connectionId={}, databaseName={}",
                    task.connectionId(), task.databaseName());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                if (task != null) {
                    upsertStatus(task.connectionId(), task.databaseName(), task.taskKey(), VectorizeStatus.FAILED,
                        truncateError(ex.getMessage()));
                    log.warn("数据库重新向量化任务失败, connectionId={}, databaseName={}, reason={}",
                        task.connectionId(), task.databaseName(), ex.getMessage());
                } else {
                    log.warn("数据库重新向量化任务失败, reason={}", ex.getMessage());
                }
            } finally {
                if (task != null) {
                    dedupeKeys.remove(task.taskKey());
                }
            }
        }
    }

    private String buildTaskKey(Long connectionId, String databaseName) {
        return connectionId + "|" + databaseName.toLowerCase();
    }

    private String normalizeDatabaseName(String databaseName) {
        return Objects.toString(databaseName, "").trim();
    }

    private void upsertStatus(Long connectionId, String databaseName, String taskKey, VectorizeStatus status, String message) {
        statusMap.put(taskKey, new VectorizeStatusRecord(
            connectionId,
            databaseName,
            status.name(),
            message,
            System.currentTimeMillis()
        ));
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

    private record VectorizeTask(Long connectionId, String databaseName, String taskKey) {
    }

    private record VectorizeStatusRecord(Long connectionId, String databaseName, String status,
                                         String message, Long updatedAt) {
    }

    private enum VectorizeStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }
}
