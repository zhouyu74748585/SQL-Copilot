package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.memory.*;
import com.sqlcopilot.studio.entity.MemoryEntryEntity;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.mapper.MemoryEntryMapper;
import com.sqlcopilot.studio.mapper.QueryHistoryMapper;
import com.sqlcopilot.studio.service.MemoryService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.model.QdrantPayloadFilter;
import com.sqlcopilot.studio.service.rag.model.QdrantPoint;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryServiceImpl implements MemoryService {

    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(?:from|join|update|into|table)\\s+([a-zA-Z0-9_$.`\"]+)");

    private final MemoryEntryMapper memoryEntryMapper;
    private final QueryHistoryMapper queryHistoryMapper;
    private final QdrantClientService qdrantClientService;
    private final RagEmbeddingService ragEmbeddingService;
    private final ObjectMapper objectMapper;
    private final String managedMemoryCollectionName;
    private final String sqlHistoryCollectionName;

    public MemoryServiceImpl(MemoryEntryMapper memoryEntryMapper,
                             QueryHistoryMapper queryHistoryMapper,
                             QdrantClientService qdrantClientService,
                             RagEmbeddingService ragEmbeddingService,
                             ObjectMapper objectMapper,
                             @Value("${rag.collection.managed-memory:managed_memory}") String managedMemoryCollectionName,
                             @Value("${rag.collection.sql-history:sql_history}") String sqlHistoryCollectionName) {
        this.memoryEntryMapper = memoryEntryMapper;
        this.queryHistoryMapper = queryHistoryMapper;
        this.qdrantClientService = qdrantClientService;
        this.ragEmbeddingService = ragEmbeddingService;
        this.objectMapper = objectMapper;
        this.managedMemoryCollectionName = managedMemoryCollectionName;
        this.sqlHistoryCollectionName = sqlHistoryCollectionName;
    }

    @Override
    public MemoryEntryPageVO pageEntries(MemoryEntryPageReq req) {
        int pageNo = normalizePageNo(req == null ? null : req.getPageNo());
        int pageSize = normalizePageSize(req == null ? null : req.getPageSize());
        String scope = normalizeMemoryScope(req == null ? null : req.getScope(), false);
        String databaseName = normalizeDatabaseName(req == null ? null : req.getDatabaseName());
        String keyword = normalizeText(req == null ? null : req.getKeyword());
        Long connectionId = normalizeConnectionId(req == null ? null : req.getConnectionId());
        long total = Optional.ofNullable(memoryEntryMapper.countPage(connectionId, databaseName, scope, keyword)).orElse(0L);
        List<MemoryEntryVO> items = memoryEntryMapper.page(
            connectionId,
            databaseName,
            scope,
            keyword,
            pageSize,
            (pageNo - 1) * pageSize
        ).stream().map(this::toMemoryEntryVO).toList();
        MemoryEntryPageVO vo = new MemoryEntryPageVO();
        vo.setPageNo(pageNo);
        vo.setPageSize(pageSize);
        vo.setTotal(total);
        vo.setHasMore((long) pageNo * pageSize < total);
        vo.setItems(items);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryEntryVO saveEntry(MemoryEntrySaveReq req) {
        String scope = normalizeMemoryScope(req == null ? null : req.getScope(), true);
        ScopeContext scopeContext = resolveScopeContext(scope, req == null ? null : req.getConnectionId(), req == null ? null : req.getDatabaseName());
        String title = normalizeOneLine(req == null ? null : req.getTitle(), 120);
        String summary = normalizeSummary(req == null ? null : req.getSummary());
        if (title.isBlank()) {
            throw new BusinessException(400, "记忆标题不能为空");
        }
        if (summary.isBlank()) {
            throw new BusinessException(400, "记忆摘要不能为空");
        }

        long now = System.currentTimeMillis();
        MemoryEntryEntity entity;
        if (req.getId() != null) {
            entity = requireMemoryEntry(req.getId());
            entity.setUpdatedAt(now);
        } else {
            entity = new MemoryEntryEntity();
            entity.setSourceType("MANUAL");
            entity.setSourceSessionId("");
            entity.setSourceHistoryIdsJson("[]");
            entity.setHitCount(0L);
            entity.setLastUsedAt(null);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
        }
        entity.setScope(scopeContext.scope());
        entity.setConnectionId(scopeContext.connectionId());
        entity.setDatabaseName(scopeContext.databaseName());
        entity.setTitle(title);
        entity.setSummary(summary);
        persistMemoryEntry(entity, extractRelatedTablesFromMemory(entity, List.of()));
        return toMemoryEntryVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeEntry(MemoryEntryRemoveReq req) {
        MemoryEntryEntity entity = requireMemoryEntry(req.getId());
        if (memoryEntryMapper.deleteById(entity.getId()) <= 0) {
            throw new BusinessException(404, "长期记忆不存在");
        }
        removeManagedMemoryVector(entity.getId());
    }

    @Override
    public MemoryHistoryPageVO pageHistories(MemoryHistoryPageReq req) {
        int pageNo = normalizePageNo(req == null ? null : req.getPageNo());
        int pageSize = normalizePageSize(req == null ? null : req.getPageSize());
        List<MemoryHistoryVO> items = collectHistoryPageItems(req, pageNo, pageSize);
        long total = countHistoryItems(req);
        MemoryHistoryPageVO vo = new MemoryHistoryPageVO();
        vo.setPageNo(pageNo);
        vo.setPageSize(pageSize);
        vo.setTotal(total);
        vo.setHasMore((long) pageNo * pageSize < total);
        vo.setItems(items);
        return vo;
    }

    @Override
    public void removeHistory(MemoryHistoryRemoveReq req) {
        QueryHistoryEntity entity = requireQueryHistory(req.getHistoryId());
        Map<Long, Map<String, Object>> payloadByHistoryId = loadHistoryPayloads(List.of(entity), entity.getConnectionId(), entity.getDatabaseName());
        if (!payloadByHistoryId.containsKey(entity.getId())) {
            throw new BusinessException(404, "历史 SQL 记忆不存在");
        }
        removeHistoryVector(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryEntryVO promoteHistory(MemoryHistoryPromoteReq req) {
        List<Long> historyIds = normalizeHistoryIds(req == null ? null : req.getHistoryIds());
        if (historyIds.isEmpty()) {
            throw new BusinessException(400, "至少需要一条历史 SQL 记忆");
        }
        List<QueryHistoryEntity> rows = sortHistories(queryHistoryMapper.listByIds(historyIds), historyIds);
        if (rows.size() != historyIds.size()) {
            throw new BusinessException(404, "存在已删除的查询历史，无法提升");
        }
        long connectionId = normalizeConnectionId(rows.get(0).getConnectionId());
        if (connectionId <= 0) {
            throw new BusinessException(400, "查询历史缺少连接信息，无法提升");
        }
        for (QueryHistoryEntity row : rows) {
            if (!Objects.equals(connectionId, normalizeConnectionId(row.getConnectionId()))) {
                throw new BusinessException(400, "仅支持提升同一连接下的历史 SQL 记忆");
            }
        }
        Map<Long, Map<String, Object>> payloadByHistoryId = loadHistoryPayloads(rows, connectionId, "");
        if (payloadByHistoryId.size() != rows.size()) {
            throw new BusinessException(400, "存在已移出记忆池的历史 SQL，无法提升");
        }

        ScopeResolution scopeResolution = resolveScopeFromHistories(rows);
        long now = System.currentTimeMillis();
        MemoryEntryEntity entity = new MemoryEntryEntity();
        entity.setScope(scopeResolution.scope());
        entity.setConnectionId(connectionId);
        entity.setDatabaseName(scopeResolution.databaseName());
        entity.setTitle(buildPromotedTitle(req == null ? null : req.getTitle(), rows, payloadByHistoryId));
        entity.setSummary(buildPromotedSummary(rows, payloadByHistoryId));
        entity.setSourceType("PROMOTED_SQL");
        entity.setSourceSessionId(resolvePromotedSourceSessionId(rows));
        entity.setSourceHistoryIdsJson(writeHistoryIds(historyIds));
        entity.setHitCount(0L);
        entity.setLastUsedAt(null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        persistMemoryEntry(entity, extractRelatedTablesFromHistoryPayloads(payloadByHistoryId, rows));
        rows.forEach(this::removeHistoryVector);
        return toMemoryEntryVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void autoUpsertSessionMemory(Long connectionId,
                                        String requestedDatabaseName,
                                        String sessionId,
                                        String summary,
                                        List<QueryHistoryEntity> sourceRows) {
        long normalizedConnectionId = normalizeConnectionId(connectionId);
        String normalizedSessionId = normalizeText(sessionId);
        String normalizedSummary = normalizeSummary(summary);
        List<QueryHistoryEntity> rows = sourceRows == null ? List.of() : sourceRows.stream().filter(Objects::nonNull).toList();
        if (normalizedConnectionId <= 0 || normalizedSessionId.isBlank() || normalizedSummary.isBlank() || rows.isEmpty()) {
            return;
        }

        ScopeResolution scopeResolution = resolveScopeFromHistories(rows, requestedDatabaseName);
        MemoryEntryEntity entity = memoryEntryMapper.findAutoSessionEntry(
            scopeResolution.scope(),
            normalizedConnectionId,
            scopeResolution.databaseName(),
            normalizedSessionId
        );
        long now = System.currentTimeMillis();
        if (entity == null) {
            entity = new MemoryEntryEntity();
            entity.setSourceType("AUTO_SESSION");
            entity.setSourceSessionId(normalizedSessionId);
            entity.setHitCount(0L);
            entity.setLastUsedAt(null);
            entity.setCreatedAt(now);
        }
        entity.setScope(scopeResolution.scope());
        entity.setConnectionId(normalizedConnectionId);
        entity.setDatabaseName(scopeResolution.databaseName());
        entity.setTitle(buildAutoMemoryTitle(rows, normalizedSummary));
        entity.setSummary(normalizedSummary);
        entity.setSourceHistoryIdsJson(writeHistoryIds(collectHistoryIds(rows)));
        entity.setUpdatedAt(now);
        persistMemoryEntry(entity, extractRelatedTablesFromRows(rows));
    }

    @Override
    public void markRetrieved(List<Long> memoryIds) {
        List<Long> ids = normalizeHistoryIds(memoryIds);
        if (ids.isEmpty()) {
            return;
        }
        memoryEntryMapper.markRetrieved(ids, System.currentTimeMillis());
    }

    @Override
    public void cleanupLegacyVectors() {
        qdrantClientService.deletePointsByFilters(sqlHistoryCollectionName, List.of(
            new QdrantPayloadFilter("entry_type", "session_summary")
        ));
        qdrantClientService.dropCollection("sql_fragment");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDatabaseArtifacts(Long connectionId, String databaseName) {
        long normalizedConnectionId = normalizeConnectionId(connectionId);
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        if (normalizedConnectionId <= 0 || normalizedDatabaseName.isBlank()) {
            return;
        }
        memoryEntryMapper.deleteByDatabase(normalizedConnectionId, normalizedDatabaseName);
        qdrantClientService.deletePointsByFilters(managedMemoryCollectionName, List.of(
            new QdrantPayloadFilter("connection_id", normalizedConnectionId),
            new QdrantPayloadFilter("database_name", normalizedDatabaseName)
        ));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeConnectionArtifacts(Long connectionId) {
        long normalizedConnectionId = normalizeConnectionId(connectionId);
        if (normalizedConnectionId <= 0) {
            return;
        }
        memoryEntryMapper.deleteByConnection(normalizedConnectionId);
        qdrantClientService.deletePointsByFilters(managedMemoryCollectionName, List.of(
            new QdrantPayloadFilter("connection_id", normalizedConnectionId)
        ));
    }

    private List<MemoryHistoryVO> collectHistoryPageItems(MemoryHistoryPageReq req, int pageNo, int pageSize) {
        int matchedOffset = Math.max(0, (pageNo - 1) * pageSize);
        int collectedMatched = 0;
        int fetchOffset = 0;
        int fetchLimit = Math.max(pageSize * 4, 40);
        List<MemoryHistoryVO> pageItems = new ArrayList<>();
        while (pageItems.size() < pageSize) {
            List<QueryHistoryEntity> batch = queryHistoryMapper.pageSuccessfulExecuteHistory(
                normalizeConnectionId(req == null ? null : req.getConnectionId()),
                normalizeDatabaseName(req == null ? null : req.getDatabaseName()),
                normalizeText(req == null ? null : req.getKeyword()),
                fetchLimit,
                fetchOffset
            );
            if (batch == null || batch.isEmpty()) {
                break;
            }
            Map<Long, Map<String, Object>> payloadByHistoryId = loadHistoryPayloads(
                batch,
                normalizeConnectionId(req == null ? null : req.getConnectionId()),
                normalizeDatabaseName(req == null ? null : req.getDatabaseName())
            );
            for (QueryHistoryEntity item : batch) {
                Map<String, Object> payload = payloadByHistoryId.get(item.getId());
                if (payload == null) {
                    continue;
                }
                if (collectedMatched < matchedOffset) {
                    collectedMatched++;
                    continue;
                }
                pageItems.add(toMemoryHistoryVO(item, payload));
                collectedMatched++;
                if (pageItems.size() >= pageSize) {
                    break;
                }
            }
            fetchOffset += fetchLimit;
        }
        return pageItems;
    }

    private long countHistoryItems(MemoryHistoryPageReq req) {
        String keyword = normalizeText(req == null ? null : req.getKeyword());
        long connectionId = normalizeConnectionId(req == null ? null : req.getConnectionId());
        String databaseName = normalizeDatabaseName(req == null ? null : req.getDatabaseName());
        if (keyword.isBlank()) {
            return Optional.ofNullable(qdrantClientService.queryCollectionMetricByFilters(
                sqlHistoryCollectionName,
                buildHistoryBaseFilters(connectionId, databaseName)
            ).getPointCount()).orElse(0L);
        }

        int fetchOffset = 0;
        int fetchLimit = 100;
        long matched = 0L;
        while (true) {
            List<QueryHistoryEntity> batch = queryHistoryMapper.pageSuccessfulExecuteHistory(
                connectionId,
                databaseName,
                keyword,
                fetchLimit,
                fetchOffset
            );
            if (batch == null || batch.isEmpty()) {
                break;
            }
            matched += loadHistoryPayloads(batch, connectionId, databaseName).size();
            fetchOffset += fetchLimit;
        }
        return matched;
    }

    private QueryHistoryEntity requireQueryHistory(Long historyId) {
        if (historyId == null) {
            throw new BusinessException(400, "historyId 不能为空");
        }
        List<QueryHistoryEntity> rows = queryHistoryMapper.listByIds(List.of(historyId));
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(404, "查询历史不存在");
        }
        return rows.get(0);
    }

    private MemoryEntryEntity requireMemoryEntry(Long id) {
        if (id == null) {
            throw new BusinessException(400, "记忆 ID 不能为空");
        }
        MemoryEntryEntity entity = memoryEntryMapper.findById(id);
        if (entity == null) {
            throw new BusinessException(404, "长期记忆不存在");
        }
        return entity;
    }

    private void persistMemoryEntry(MemoryEntryEntity entity, List<String> relatedTables) {
        String vectorText = buildMemoryDocumentText(entity);
        List<Float> vector = ragEmbeddingService.embedText(vectorText);
        if (vector == null || vector.isEmpty()) {
            throw new BusinessException(500, "长期记忆向量化失败");
        }
        if (entity.getId() == null) {
            memoryEntryMapper.insert(entity);
        } else if (memoryEntryMapper.update(entity) <= 0) {
            throw new BusinessException(404, "长期记忆不存在");
        }
        qdrantClientService.ensureCollection(managedMemoryCollectionName, vector.size());
        qdrantClientService.upsertPoints(
            managedMemoryCollectionName,
            List.of(new QdrantPoint(
                "managed-memory-" + entity.getId(),
                vector,
                buildManagedMemoryPayload(entity, relatedTables)
            ))
        );
    }

    private void removeManagedMemoryVector(Long memoryId) {
        qdrantClientService.deletePointsByFilters(managedMemoryCollectionName, List.of(
            new QdrantPayloadFilter("memory_id", memoryId)
        ));
    }

    private void removeHistoryVector(QueryHistoryEntity entity) {
        if (entity == null || entity.getId() == null || entity.getId() <= 0) {
            return;
        }
        qdrantClientService.deletePointsByFilters(sqlHistoryCollectionName, List.of(
            new QdrantPayloadFilter("entry_type", "history_query"),
            new QdrantPayloadFilter("history_id", entity.getId())
        ));
        qdrantClientService.deletePointsByFilters(sqlHistoryCollectionName, buildLegacyHistoryDeleteFilters(entity));
    }

    private Map<String, Object> buildManagedMemoryPayload(MemoryEntryEntity entity, List<String> relatedTables) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memory_id", entity.getId());
        payload.put("scope", entity.getScope());
        payload.put("connection_id", entity.getConnectionId());
        payload.put("database_name", normalizeDatabaseName(entity.getDatabaseName()));
        payload.put("title", normalizeText(entity.getTitle()));
        payload.put("summary", normalizeSummary(entity.getSummary()));
        payload.put("source_type", normalizeText(entity.getSourceType()));
        payload.put("source_session_id", normalizeText(entity.getSourceSessionId()));
        payload.put("related_tables", relatedTables == null ? List.of() : relatedTables);
        payload.put("updated_at", entity.getUpdatedAt());
        return payload;
    }

    private String buildMemoryDocumentText(MemoryEntryEntity entity) {
        return """
            记忆标题: %s
            记忆摘要: %s
            作用域: %s
            连接ID: %s
            数据库: %s
            来源: %s
            """.formatted(
            normalizeText(entity.getTitle()),
            normalizeSummary(entity.getSummary()),
            normalizeText(entity.getScope()),
            entity.getConnectionId(),
            normalizeDatabaseName(entity.getDatabaseName()),
            normalizeText(entity.getSourceType())
        ).trim();
    }

    private Map<Long, Map<String, Object>> loadHistoryPayloads(List<QueryHistoryEntity> rows,
                                                               Long connectionId,
                                                               String databaseName) {
        List<Long> historyIds = collectHistoryIds(rows);
        if (historyIds.isEmpty()) {
            return Map.of();
        }
        List<QdrantPayloadFilter> baseFilters = buildHistoryBaseFilters(connectionId, databaseName);
        List<QdrantScoredPoint> points = qdrantClientService.scrollPointsByFieldValues(
            sqlHistoryCollectionName,
            "history_id",
            historyIds,
            baseFilters,
            Math.max(historyIds.size(), 32)
        );
        Map<Long, Map<String, Object>> payloadByHistoryId = new LinkedHashMap<>();
        for (QdrantScoredPoint point : points) {
            Map<String, Object> payload = point == null ? null : point.getPayload();
            Long historyId = asLong(payload == null ? null : payload.get("history_id"));
            if (historyId != null && historyId > 0) {
                payloadByHistoryId.put(historyId, payload);
            }
        }
        if (payloadByHistoryId.size() >= historyIds.size()) {
            return payloadByHistoryId;
        }

        Map<String, Long> pointIdToHistoryId = new LinkedHashMap<>();
        for (QueryHistoryEntity row : rows) {
            if (row == null || row.getId() == null || row.getId() <= 0) {
                continue;
            }
            pointIdToHistoryId.put(buildSqlHistoryPointId(row.getConnectionId(), row.getDatabaseName(), row.getId()), row.getId());
        }
        if (pointIdToHistoryId.isEmpty()) {
            return payloadByHistoryId;
        }

        List<QdrantScoredPoint> legacyPoints = qdrantClientService.getPointsByIds(
            sqlHistoryCollectionName,
            new ArrayList<>(pointIdToHistoryId.keySet())
        );
        for (QdrantScoredPoint point : legacyPoints) {
            if (point == null) {
                continue;
            }
            Map<String, Object> payload = point.getPayload() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(point.getPayload());
            Long historyId = asLong(payload.get("history_id"));
            if (historyId == null || historyId <= 0) {
                historyId = pointIdToHistoryId.get(point.getId());
                if (historyId != null && historyId > 0) {
                    payload.put("history_id", historyId);
                }
            }
            if (historyId != null && historyId > 0 && matchesHistoryBasePayloadFilters(payload, baseFilters)) {
                payloadByHistoryId.putIfAbsent(historyId, payload);
            }
        }
        return payloadByHistoryId;
    }

    private boolean matchesHistoryBasePayloadFilters(Map<String, Object> payload, List<QdrantPayloadFilter> filters) {
        if (payload == null || payload.isEmpty() || filters == null || filters.isEmpty()) {
            return true;
        }
        for (QdrantPayloadFilter filter : filters) {
            if (filter == null) {
                continue;
            }
            String key = normalizeText(filter.key());
            Object expectedValue = filter.value();
            if (key.isBlank() || expectedValue == null) {
                continue;
            }
            Object actualValue = payload.get(key);
            if ("connection_id".equals(key) || "created_at".equals(key) || "history_id".equals(key)) {
                if (!Objects.equals(asLong(expectedValue), asLong(actualValue))) {
                    return false;
                }
                continue;
            }
            if (!Objects.equals(normalizeText(Objects.toString(expectedValue, "")), normalizeText(Objects.toString(actualValue, "")))) {
                return false;
            }
        }
        return true;
    }

    private List<QdrantPayloadFilter> buildLegacyHistoryDeleteFilters(QueryHistoryEntity entity) {
        List<QdrantPayloadFilter> filters = new ArrayList<>();
        filters.add(new QdrantPayloadFilter("entry_type", "history_query"));
        filters.add(new QdrantPayloadFilter("connection_id", normalizeConnectionId(entity == null ? null : entity.getConnectionId())));
        String normalizedDatabaseName = normalizeDatabaseName(entity == null ? null : entity.getDatabaseName());
        if (!normalizedDatabaseName.isBlank()) {
            filters.add(new QdrantPayloadFilter("database_name", normalizedDatabaseName));
        }
        String sessionId = normalizeText(entity == null ? null : entity.getSessionId());
        if (!sessionId.isBlank()) {
            filters.add(new QdrantPayloadFilter("session_id", sessionId));
        }
        String sqlText = normalizeText(entity == null ? null : entity.getSqlText());
        if (!sqlText.isBlank()) {
            filters.add(new QdrantPayloadFilter("sql_text", sqlText));
        }
        if (entity != null && entity.getCreatedAt() != null && entity.getCreatedAt() > 0) {
            filters.add(new QdrantPayloadFilter("created_at", entity.getCreatedAt()));
        }
        return filters;
    }

    private String buildSqlHistoryPointId(Long connectionId, String databaseName, Long historyId) {
        String joined = String.join("|",
            "sql_history",
            String.valueOf(normalizeConnectionId(connectionId)),
            normalizePointIdDatabaseName(databaseName),
            String.valueOf(historyId == null ? 0L : historyId)
        );
        return UUID.nameUUIDFromBytes(joined.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizePointIdDatabaseName(String databaseName) {
        String normalized = normalizeDatabaseName(databaseName);
        return normalized.isBlank() ? "__default__" : normalized;
    }

    private List<QdrantPayloadFilter> buildHistoryBaseFilters(Long connectionId, String databaseName) {
        List<QdrantPayloadFilter> filters = new ArrayList<>();
        filters.add(new QdrantPayloadFilter("entry_type", "history_query"));
        long normalizedConnectionId = normalizeConnectionId(connectionId);
        if (normalizedConnectionId > 0) {
            filters.add(new QdrantPayloadFilter("connection_id", normalizedConnectionId));
        }
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        if (!normalizedDatabaseName.isBlank()) {
            filters.add(new QdrantPayloadFilter("database_name", normalizedDatabaseName));
        }
        return filters;
    }

    private ScopeContext resolveScopeContext(String scope, Long connectionId, String databaseName) {
        long normalizedConnectionId = normalizeConnectionId(connectionId);
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        if ("CONNECTION".equals(scope)) {
            if (normalizedConnectionId <= 0) {
                throw new BusinessException(400, "连接级记忆必须指定 connectionId");
            }
            return new ScopeContext("CONNECTION", normalizedConnectionId, "");
        }
        if ("DATABASE".equals(scope)) {
            if (normalizedConnectionId <= 0 || normalizedDatabaseName.isBlank()) {
                throw new BusinessException(400, "数据库级记忆必须指定 connectionId 和 databaseName");
            }
            return new ScopeContext("DATABASE", normalizedConnectionId, normalizedDatabaseName);
        }
        throw new BusinessException(400, "记忆作用域仅支持 CONNECTION / DATABASE");
    }

    private ScopeResolution resolveScopeFromHistories(List<QueryHistoryEntity> rows) {
        return resolveScopeFromHistories(rows, "");
    }

    private ScopeResolution resolveScopeFromHistories(List<QueryHistoryEntity> rows, String fallbackDatabaseName) {
        Set<String> databaseNames = new LinkedHashSet<>();
        if (rows != null) {
            for (QueryHistoryEntity row : rows) {
                String databaseName = normalizeDatabaseName(row == null ? null : row.getDatabaseName());
                if (!databaseName.isBlank()) {
                    databaseNames.add(databaseName);
                }
            }
        }
        String normalizedFallbackDatabaseName = normalizeDatabaseName(fallbackDatabaseName);
        if (databaseNames.isEmpty() && !normalizedFallbackDatabaseName.isBlank()) {
            databaseNames.add(normalizedFallbackDatabaseName);
        }
        if (databaseNames.size() == 1) {
            return new ScopeResolution("DATABASE", databaseNames.iterator().next());
        }
        return new ScopeResolution("CONNECTION", "");
    }

    private String buildAutoMemoryTitle(List<QueryHistoryEntity> rows, String summary) {
        for (QueryHistoryEntity row : rows) {
            String prompt = normalizeOneLine(row == null ? null : row.getPromptText(), 80);
            if (!prompt.isBlank()) {
                return prompt;
            }
        }
        for (QueryHistoryEntity row : rows) {
            String sql = normalizeOneLine(row == null ? null : row.getSqlText(), 80);
            if (!sql.isBlank()) {
                return sql;
            }
        }
        return normalizeOneLine(summary, 80);
    }

    private String buildPromotedTitle(String preferredTitle,
                                      List<QueryHistoryEntity> rows,
                                      Map<Long, Map<String, Object>> payloadByHistoryId) {
        String normalizedPreferredTitle = normalizeOneLine(preferredTitle, 120);
        if (!normalizedPreferredTitle.isBlank()) {
            return normalizedPreferredTitle;
        }
        for (QueryHistoryEntity row : rows) {
            String prompt = normalizeOneLine(row == null ? null : row.getPromptText(), 120);
            if (!prompt.isBlank()) {
                return prompt;
            }
        }
        for (QueryHistoryEntity row : rows) {
            Map<String, Object> payload = payloadByHistoryId.get(row.getId());
            String semantic = normalizeOneLine(payloadString(payload, "semantic_description"), 120);
            if (!semantic.isBlank()) {
                return semantic;
            }
        }
        for (QueryHistoryEntity row : rows) {
            String sql = normalizeOneLine(row == null ? null : row.getSqlText(), 120);
            if (!sql.isBlank()) {
                return sql;
            }
        }
        return "历史 SQL 记忆";
    }

    private String buildPromotedSummary(List<QueryHistoryEntity> rows,
                                        Map<Long, Map<String, Object>> payloadByHistoryId) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (QueryHistoryEntity row : rows) {
            Map<String, Object> payload = payloadByHistoryId.get(row.getId());
            String semantic = normalizeText(payloadString(payload, "semantic_description"));
            if (!semantic.isBlank()) {
                lines.add("语义: " + semantic);
            }
            String prompt = normalizeText(row.getPromptText());
            if (!prompt.isBlank()) {
                lines.add("问题: " + prompt);
            }
            String sql = normalizeOneLine(row.getSqlText(), 200);
            if (!sql.isBlank()) {
                lines.add("SQL: " + sql);
            }
        }
        if (lines.isEmpty()) {
            return "历史 SQL 提升记忆";
        }
        return String.join("\n", lines);
    }

    private String resolvePromotedSourceSessionId(List<QueryHistoryEntity> rows) {
        LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
        for (QueryHistoryEntity row : rows) {
            String sessionId = normalizeText(row == null ? null : row.getSessionId());
            if (!sessionId.isBlank()) {
                sessionIds.add(sessionId);
            }
        }
        return sessionIds.size() == 1 ? sessionIds.iterator().next() : "";
    }

    private List<String> extractRelatedTablesFromHistoryPayloads(Map<Long, Map<String, Object>> payloadByHistoryId,
                                                                 List<QueryHistoryEntity> rows) {
        LinkedHashSet<String> relatedTables = new LinkedHashSet<>();
        for (QueryHistoryEntity row : rows) {
            Map<String, Object> payload = payloadByHistoryId.get(row.getId());
            relatedTables.addAll(payloadStringList(payload, "tables"));
        }
        if (!relatedTables.isEmpty()) {
            return new ArrayList<>(relatedTables);
        }
        return extractRelatedTablesFromRows(rows);
    }

    private List<String> extractRelatedTablesFromMemory(MemoryEntryEntity entity, List<QueryHistoryEntity> rows) {
        LinkedHashSet<String> relatedTables = new LinkedHashSet<>(extractRelatedTablesFromRows(rows));
        relatedTables.addAll(extractRelatedTablesFromText(entity == null ? null : entity.getTitle()));
        relatedTables.addAll(extractRelatedTablesFromText(entity == null ? null : entity.getSummary()));
        return new ArrayList<>(relatedTables);
    }

    private List<String> extractRelatedTablesFromRows(List<QueryHistoryEntity> rows) {
        LinkedHashSet<String> relatedTables = new LinkedHashSet<>();
        if (rows != null) {
            for (QueryHistoryEntity row : rows) {
                relatedTables.addAll(extractRelatedTablesFromText(row == null ? null : row.getSqlText()));
            }
        }
        return new ArrayList<>(relatedTables);
    }

    private List<String> extractRelatedTablesFromText(String text) {
        String normalizedText = normalizeText(text);
        if (normalizedText.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> relatedTables = new LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            String candidate = normalizeIdentifier(matcher.group(1));
            if (!candidate.isBlank()) {
                relatedTables.add(candidate);
            }
        }
        return new ArrayList<>(relatedTables);
    }

    private MemoryEntryVO toMemoryEntryVO(MemoryEntryEntity entity) {
        MemoryEntryVO vo = new MemoryEntryVO();
        vo.setId(entity.getId());
        vo.setScope(entity.getScope());
        vo.setConnectionId(entity.getConnectionId());
        vo.setDatabaseName(normalizeDatabaseName(entity.getDatabaseName()));
        vo.setTitle(normalizeText(entity.getTitle()));
        vo.setSummary(normalizeSummary(entity.getSummary()));
        vo.setSourceType(normalizeText(entity.getSourceType()));
        vo.setSourceSessionId(normalizeText(entity.getSourceSessionId()));
        vo.setSourceHistoryIds(parseHistoryIds(entity.getSourceHistoryIdsJson()));
        vo.setHitCount(Optional.ofNullable(entity.getHitCount()).orElse(0L));
        vo.setLastUsedAt(entity.getLastUsedAt());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private MemoryHistoryVO toMemoryHistoryVO(QueryHistoryEntity entity, Map<String, Object> payload) {
        MemoryHistoryVO vo = new MemoryHistoryVO();
        vo.setHistoryId(entity.getId());
        vo.setConnectionId(entity.getConnectionId());
        vo.setSessionId(normalizeText(entity.getSessionId()));
        vo.setPromptText(normalizeText(entity.getPromptText()));
        vo.setSqlText(normalizeText(entity.getSqlText()));
        vo.setDatabaseName(normalizeDatabaseName(entity.getDatabaseName()));
        vo.setSemanticSummary(payloadString(payload, "semantic_description"));
        vo.setTables(payloadStringList(payload, "tables"));
        vo.setSourceType(payloadString(payload, "source_type"));
        vo.setExecutionMs(entity.getExecutionMs());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    private List<QueryHistoryEntity> sortHistories(List<QueryHistoryEntity> rows, List<Long> orderedIds) {
        Map<Long, QueryHistoryEntity> byId = new LinkedHashMap<>();
        if (rows != null) {
            for (QueryHistoryEntity row : rows) {
                if (row != null && row.getId() != null) {
                    byId.put(row.getId(), row);
                }
            }
        }
        List<QueryHistoryEntity> sorted = new ArrayList<>();
        for (Long id : orderedIds) {
            QueryHistoryEntity row = byId.get(id);
            if (row != null) {
                sorted.add(row);
            }
        }
        return sorted;
    }

    private List<Long> collectHistoryIds(List<QueryHistoryEntity> rows) {
        List<Long> ids = new ArrayList<>();
        if (rows != null) {
            for (QueryHistoryEntity row : rows) {
                if (row != null && row.getId() != null && row.getId() > 0) {
                    ids.add(row.getId());
                }
            }
        }
        return ids;
    }

    private List<Long> normalizeHistoryIds(List<Long> values) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (values != null) {
            for (Long value : values) {
                if (value != null && value > 0) {
                    ids.add(value);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private List<Long> parseHistoryIds(String json) {
        String normalized = normalizeText(json);
        if (normalized.isBlank()) {
            return List.of();
        }
        try {
            return normalizeHistoryIds(objectMapper.readValue(normalized, LONG_LIST_TYPE));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeHistoryIds(List<Long> historyIds) {
        try {
            return objectMapper.writeValueAsString(normalizeHistoryIds(historyIds));
        } catch (Exception ex) {
            throw new BusinessException(500, "序列化来源历史失败");
        }
    }

    private Long normalizeConnectionId(Long connectionId) {
        if (connectionId == null || connectionId <= 0) {
            return 0L;
        }
        return connectionId;
    }

    private String normalizeDatabaseName(String databaseName) {
        String normalized = normalizeText(databaseName);
        if ("__default__".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private String normalizeMemoryScope(String scope, boolean required) {
        String normalized = normalizeText(scope).toUpperCase(Locale.ROOT);
        if (!required && normalized.isBlank()) {
            return "";
        }
        if ("CONNECTION".equals(normalized) || "DATABASE".equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(400, "记忆作用域仅支持 CONNECTION / DATABASE");
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo <= 0 ? DEFAULT_PAGE_NO : pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String normalizeText(String value) {
        return Objects.toString(value, "").trim();
    }

    private String normalizeSummary(String value) {
        return normalizeText(value);
    }

    private String normalizeOneLine(String value, int maxLength) {
        String normalized = normalizeText(value).replaceAll("\\s+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private String normalizeIdentifier(String value) {
        String normalized = normalizeText(value)
            .replace("`", "")
            .replace("\"", "")
            .replace("[", "")
            .replace("]", "");
        if (normalized.contains(".")) {
            String[] parts = normalized.split("\\.");
            normalized = parts[parts.length - 1];
        }
        return normalized;
    }

    private String payloadString(Map<String, Object> payload, String key) {
        return normalizeText(payload == null ? null : Objects.toString(payload.get(key), ""));
    }

    private List<String> payloadStringList(Map<String, Object> payload, String key) {
        if (payload == null || !(payload.get(key) instanceof List<?> rawList)) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : rawList) {
            String text = normalizeIdentifier(Objects.toString(item, ""));
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return new ArrayList<>(values);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = normalizeText(Objects.toString(value, ""));
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private record ScopeContext(String scope, Long connectionId, String databaseName) {
    }

    private record ScopeResolution(String scope, String databaseName) {
    }
}
