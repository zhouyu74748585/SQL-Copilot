package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.ai.ChartConfigVO;
import com.sqlcopilot.studio.dto.editor.*;
import com.sqlcopilot.studio.dto.schema.ErGraphVO;
import com.sqlcopilot.studio.dto.sql.QueryCellVO;
import com.sqlcopilot.studio.dto.sql.QueryRowVO;
import com.sqlcopilot.studio.entity.ErGraphSnapshotEntity;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.mapper.ErGraphSnapshotMapper;
import com.sqlcopilot.studio.mapper.QueryHistoryMapper;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.EditorService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.util.BusinessException;
import com.sqlcopilot.studio.util.ResultSetConverter;
import com.sqlcopilot.studio.util.SqlClassifier;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class EditorServiceImpl implements EditorService {

    private static final long MAX_CHART_IMAGE_BYTES = 8L * 1024L * 1024L;

    private final QueryHistoryMapper queryHistoryMapper;
    private final ErGraphSnapshotMapper erGraphSnapshotMapper;
    private final ConnectionService connectionService;
    private final ObjectMapper objectMapper;
    private final QdrantClientService qdrantClientService;
    private final String sqlHistoryCollectionName;

    public EditorServiceImpl(QueryHistoryMapper queryHistoryMapper,
                             ErGraphSnapshotMapper erGraphSnapshotMapper,
                             ConnectionService connectionService,
                             ObjectMapper objectMapper,
                             QdrantClientService qdrantClientService,
                             @org.springframework.beans.factory.annotation.Value("${rag.collection.sql-history:sql_history}") String sqlHistoryCollectionName) {
        this.queryHistoryMapper = queryHistoryMapper;
        this.erGraphSnapshotMapper = erGraphSnapshotMapper;
        this.connectionService = connectionService;
        this.objectMapper = objectMapper;
        this.qdrantClientService = qdrantClientService;
        this.sqlHistoryCollectionName = sqlHistoryCollectionName;
    }

    @Override
    public List<QueryHistoryVO> listHistory(Long connectionId, Integer limit) {
        int actualLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 200);
        return queryHistoryMapper.listByConnection(connectionId, actualLimit).stream().map(this::toHistoryVO).toList();
    }

    @Override
    public QueryHistorySessionPageVO pageHistorySessions(Long connectionId, Integer pageNo, Integer pageSize, String keyword) {
        int actualPageNo = (pageNo == null || pageNo <= 0) ? 1 : pageNo;
        int actualPageSize = (pageSize == null || pageSize <= 0) ? 20 : Math.min(pageSize, 100);
        int offset = (actualPageNo - 1) * actualPageSize;
        String normalizedKeyword = Objects.toString(keyword, "").trim();

        long total = Objects.requireNonNullElse(queryHistoryMapper.countSessions(connectionId, normalizedKeyword), 0L);
        List<QueryHistorySessionVO> items = queryHistoryMapper.pageSessions(connectionId, normalizedKeyword, actualPageSize, offset)
            .stream()
            .map(this::normalizeSessionTitle)
            .toList();

        QueryHistorySessionPageVO vo = new QueryHistorySessionPageVO();
        vo.setPageNo(actualPageNo);
        vo.setPageSize(actualPageSize);
        vo.setTotal(total);
        vo.setHasMore((long) offset + items.size() < total);
        vo.setItems(items);
        return vo;
    }

    @Override
    public List<QueryHistoryVO> listHistoryBySession(Long connectionId, String sessionId, Integer limit) {
        String normalizedSessionId = Objects.toString(sessionId, "").trim();
        if (normalizedSessionId.isBlank()) {
            throw new BusinessException(400, "会话 ID 不能为空");
        }
        int actualLimit = (limit == null || limit <= 0) ? 1000 : Math.min(limit, 5000);
        return queryHistoryMapper.listBySession(connectionId, normalizedSessionId, actualLimit).stream().map(this::toHistoryVO).toList();
    }

    @Override
    public ErGraphSnapshotPageVO pageErGraphSnapshots(Long connectionId, Integer pageNo, Integer pageSize, String keyword) {
        if (connectionId == null) {
            throw new BusinessException(400, "connectionId 不能为空");
        }
        int actualPageNo = (pageNo == null || pageNo <= 0) ? 1 : pageNo;
        int actualPageSize = (pageSize == null || pageSize <= 0) ? 20 : Math.min(pageSize, 100);
        int offset = (actualPageNo - 1) * actualPageSize;
        String normalizedKeyword = Objects.toString(keyword, "").trim();

        long total = Objects.requireNonNullElse(erGraphSnapshotMapper.countByConnection(connectionId, normalizedKeyword), 0L);
        List<ErGraphSnapshotSummaryVO> items = erGraphSnapshotMapper.pageByConnection(connectionId, normalizedKeyword, actualPageSize, offset)
            .stream()
            .map(this::toSnapshotSummaryVO)
            .toList();

        ErGraphSnapshotPageVO vo = new ErGraphSnapshotPageVO();
        vo.setPageNo(actualPageNo);
        vo.setPageSize(actualPageSize);
        vo.setTotal(total);
        vo.setHasMore((long) offset + items.size() < total);
        vo.setItems(items);
        return vo;
    }

    @Override
    public ErGraphSnapshotVO getErGraphSnapshotDetail(Long snapshotId) {
        if (snapshotId == null) {
            throw new BusinessException(400, "快照 ID 不能为空");
        }
        ErGraphSnapshotEntity entity = erGraphSnapshotMapper.getById(snapshotId);
        if (entity == null) {
            throw new BusinessException(404, "ER 图快照不存在");
        }
        ErGraphSnapshotVO vo = toSnapshotDetailVO(entity);
        if (vo.getGraph() == null) {
            throw new BusinessException(500, "ER 图快照数据损坏");
        }
        return vo;
    }

    @Override
    public void renameErGraphSnapshot(RenameErGraphSnapshotReq req) {
        String snapshotName = safe(req.getSnapshotName());
        if (snapshotName.isBlank()) {
            throw new BusinessException(400, "快照名称不能为空");
        }
        String normalizedName = snapshotName.length() > 80 ? snapshotName.substring(0, 80) : snapshotName;
        // 关键操作：按连接 + 快照双条件更新，避免跨连接误改名。
        int affected = erGraphSnapshotMapper.updateSnapshotName(
            req.getConnectionId(),
            req.getSnapshotId(),
            normalizedName,
            System.currentTimeMillis()
        );
        if (affected <= 0) {
            throw new BusinessException(404, "ER 图快照不存在或已删除");
        }
    }

    @Override
    public void removeErGraphSnapshot(DeleteErGraphSnapshotReq req) {
        // 关键操作：按连接 + 快照双条件删除，确保仅删除当前连接下的目标快照。
        int affected = erGraphSnapshotMapper.deleteById(req.getConnectionId(), req.getSnapshotId());
        if (affected <= 0) {
            throw new BusinessException(404, "ER 图快照不存在或已删除");
        }
    }

    @Override
    public void removeHistorySession(DeleteHistorySessionReq req) {
        String normalizedSessionId = Objects.toString(req.getSessionId(), "").trim();
        if (normalizedSessionId.isBlank()) {
            throw new BusinessException(400, "会话 ID 不能为空");
        }
        // 关键操作：按连接与会话 ID 一次性删除整组历史记录，避免残留碎片数据。
        queryHistoryMapper.deleteBySession(req.getConnectionId(), normalizedSessionId);
        try {
            qdrantClientService.deletePointsByFilter(sqlHistoryCollectionName, req.getConnectionId(), "", normalizedSessionId);
        } catch (Exception ignored) {
            // 删除向量失败不阻塞主流程。
        }
    }

    @Override
    public void saveHistory(SaveQueryHistoryReq req) {
        QueryHistoryEntity entity = new QueryHistoryEntity();
        entity.setConnectionId(req.getConnectionId());
        entity.setSessionId(req.getSessionId());
        entity.setPromptText(req.getPromptText());
        entity.setSqlText(safe(req.getSqlText()));
        entity.setHistoryType(resolveHistoryType(req.getHistoryType()));
        entity.setActionType(safe(req.getActionType()));
        entity.setAssistantContent(safe(req.getAssistantContent()));
        entity.setDatabaseName(safe(req.getDatabaseName()));
        entity.setChartConfigJson(safe(req.getChartConfigJson()));
        entity.setChartImageCacheKey(safe(req.getChartImageCacheKey()));
        entity.setStructuredContextJson(safe(req.getStructuredContextJson()));
        entity.setTokenEstimate(req.getTokenEstimate());
        entity.setMemoryEnabled(Boolean.TRUE.equals(req.getMemoryEnabled()) ? 1 : 0);
        entity.setExecutionMs(req.getExecutionMs());
        entity.setSuccessFlag(Boolean.TRUE.equals(req.getSuccess()) ? 1 : 0);
        entity.setCreatedAt(System.currentTimeMillis());
        queryHistoryMapper.insert(entity);
    }

    @Override
    public void saveErGraphSnapshot(ErGraphSnapshotSaveReq req) {
        String snapshotName = safe(req.getSnapshotName());
        if (snapshotName.isBlank()) {
            throw new BusinessException(400, "快照名称不能为空");
        }
        String databaseName = safe(req.getDatabaseName());
        if (databaseName.isBlank()) {
            throw new BusinessException(400, "数据库名称不能为空");
        }
        List<String> selectedTableNames = normalizeSelectedTableNames(req.getSelectedTableNames());
        if (selectedTableNames.isEmpty()) {
            throw new BusinessException(400, "至少需要一张已选表");
        }
        if (req.getGraph() == null) {
            throw new BusinessException(400, "ER 图结果不能为空");
        }

        ErGraphSnapshotEntity entity = new ErGraphSnapshotEntity();
        entity.setConnectionId(req.getConnectionId());
        entity.setDatabaseName(databaseName);
        entity.setSnapshotName(snapshotName.length() > 80 ? snapshotName.substring(0, 80) : snapshotName);
        entity.setSelectedTablesJson(writeJson(selectedTableNames));
        entity.setModelName(safe(req.getModelName()));
        entity.setLayoutMode(safe(req.getLayoutMode()));
        entity.setAiConfidenceThreshold(normalizeSnapshotThreshold(req.getAiConfidenceThreshold()));
        entity.setIncludeAiInference(Boolean.FALSE.equals(req.getIncludeAiInference()) ? 0 : 1);
        entity.setGraphJson(writeJson(req.getGraph()));
        long now = System.currentTimeMillis();
        entity.setUpdatedAt(now);
        Long snapshotId = req.getSnapshotId();
        // 关键操作：传入快照主键时执行原记录更新，避免重复新增快照。
        if (snapshotId != null && snapshotId > 0) {
            entity.setId(snapshotId);
            int updated = erGraphSnapshotMapper.updateSnapshotContent(entity);
            if (updated <= 0) {
                throw new BusinessException(404, "ER 图快照不存在或无权限更新");
            }
            return;
        }
        entity.setCreatedAt(now);
        erGraphSnapshotMapper.insert(entity);
    }

    @Override
    public ChartCacheSaveVO saveChartCache(ChartCacheSaveReq req) {
        String base64Text = safe(req.getImageBase64Png());
        if (base64Text.startsWith("data:image/png;base64,")) {
            base64Text = base64Text.substring("data:image/png;base64,".length());
        }
        byte[] imageBytes;
        try {
            imageBytes = Base64.getDecoder().decode(base64Text);
        } catch (Exception ex) {
            throw new BusinessException(400, "图表图片 Base64 内容无效");
        }
        if (imageBytes.length == 0) {
            throw new BusinessException(400, "图表图片内容为空");
        }
        if (imageBytes.length > MAX_CHART_IMAGE_BYTES) {
            throw new BusinessException(400, "图表图片超过大小限制（8MB）");
        }

        String safeSessionId = sanitizePathSegment(req.getSessionId());
        if (safeSessionId.isBlank()) {
            throw new BusinessException(400, "会话 ID 非法");
        }
        String fileName = sanitizeFileName(req.getSuggestedFileName());
        if (fileName.isBlank()) {
            fileName = "chart-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        Path baseDir = Path.of("exports", "chart-cache").toAbsolutePath().normalize();
        Path relativePath = Path.of(
            String.valueOf(req.getConnectionId()),
            safeSessionId,
            fileName + ".png"
        );
        Path target = baseDir.resolve(relativePath).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BusinessException(400, "图表缓存路径非法");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, imageBytes);
        } catch (Exception ex) {
            throw new BusinessException(500, "图表缓存保存失败: " + ex.getMessage());
        }

        String cacheKey = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(relativePath.toString().getBytes(StandardCharsets.UTF_8));
        ChartCacheSaveVO vo = new ChartCacheSaveVO();
        vo.setCacheKey(cacheKey);
        vo.setFilePath(target.toAbsolutePath().toString());
        vo.setWidth(req.getWidth() == null ? 0 : req.getWidth());
        vo.setHeight(req.getHeight() == null ? 0 : req.getHeight());
        return vo;
    }

    @Override
    public ChartCacheReadVO readChartCache(String cacheKey) {
        String normalizedKey = safe(cacheKey);
        if (normalizedKey.isBlank()) {
            throw new BusinessException(400, "cacheKey 不能为空");
        }

        String relative;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(normalizedKey);
            relative = new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new BusinessException(400, "cacheKey 非法");
        }
        Path baseDir = Path.of("exports", "chart-cache").toAbsolutePath().normalize();
        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BusinessException(400, "cacheKey 非法");
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new BusinessException(404, "图表缓存不存在");
        }
        try {
            byte[] imageBytes = Files.readAllBytes(target);
            ChartCacheReadVO vo = new ChartCacheReadVO();
            vo.setCacheKey(normalizedKey);
            vo.setDataUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes));
            return vo;
        } catch (Exception ex) {
            throw new BusinessException(500, "图表缓存读取失败: " + ex.getMessage());
        }
    }

    @Override
    public ExportResultVO exportResult(ExportReq req) {
        if (!SqlClassifier.isQuery(req.getSqlText())) {
            throw new BusinessException(400, "仅支持导出查询 SQL 结果");
        }

        List<QueryRowVO> rows = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        try (Connection connection = connectionService.openTargetConnection(req.getConnectionId());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(req.getSqlText())) {
            ResultSetConverter.readColumns(resultSet.getMetaData()).forEach(column -> headers.add(column.getColumnName()));
            rows = ResultSetConverter.readRows(resultSet, 5000);
        } catch (Exception ex) {
            throw new BusinessException(500, "导出失败: " + ex.getMessage());
        }

        String format = req.getFormat().toUpperCase();
        String fileName = req.getFileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = "sql_copilot_export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        }

        Path exportDir = Path.of("exports");
        try {
            Files.createDirectories(exportDir);
            Path path;
            if ("CSV".equals(format)) {
                path = exportDir.resolve(fileName + ".csv");
                writeCsv(path, headers, rows);
            } else if ("JSON".equals(format)) {
                path = exportDir.resolve(fileName + ".json");
                writeJson(path, rows);
            } else {
                throw new BusinessException(400, "不支持的导出格式: " + req.getFormat());
            }

            ExportResultVO vo = new ExportResultVO();
            vo.setSuccess(Boolean.TRUE);
            vo.setFilePath(path.toAbsolutePath().toString());
            vo.setMessage("导出成功");
            return vo;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "导出文件写入失败: " + ex.getMessage());
        }
    }

    private void writeCsv(Path path, List<String> headers, List<QueryRowVO> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", headers));
            writer.newLine();
            for (QueryRowVO row : rows) {
                List<String> values = new ArrayList<>();
                for (QueryCellVO cell : row.getCells()) {
                    String value = cell.getCellValue() == null ? "" : cell.getCellValue().replace("\"", "\"\"");
                    values.add("\"" + value + "\"");
                }
                writer.write(String.join(",", values));
                writer.newLine();
            }
        }
    }

    private void writeJson(Path path, List<QueryRowVO> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("[");
            for (int i = 0; i < rows.size(); i++) {
                QueryRowVO row = rows.get(i);
                writer.write("{");
                for (int j = 0; j < row.getCells().size(); j++) {
                    QueryCellVO cell = row.getCells().get(j);
                    String value = cell.getCellValue() == null ? "" : cell.getCellValue().replace("\"", "\\\"");
                    writer.write("\"" + cell.getColumnName() + "\":\"" + value + "\"");
                    if (j < row.getCells().size() - 1) {
                        writer.write(",");
                    }
                }
                writer.write("}");
                if (i < rows.size() - 1) {
                    writer.write(",");
                }
            }
            writer.write("]");
        }
    }

    private QueryHistoryVO toHistoryVO(QueryHistoryEntity entity) {
        QueryHistoryVO vo = new QueryHistoryVO();
        vo.setId(entity.getId());
        vo.setConnectionId(entity.getConnectionId());
        vo.setSessionId(entity.getSessionId());
        vo.setPromptText(entity.getPromptText());
        vo.setSqlText(entity.getSqlText());
        vo.setHistoryType(resolveHistoryType(entity.getHistoryType()));
        vo.setActionType(safe(entity.getActionType()));
        vo.setAssistantContent(safe(entity.getAssistantContent()));
        vo.setDatabaseName(safe(entity.getDatabaseName()));
        vo.setChartConfig(parseChartConfig(entity.getChartConfigJson()));
        vo.setChartImageCacheKey(safe(entity.getChartImageCacheKey()));
        vo.setExecutionMs(entity.getExecutionMs());
        vo.setSuccess(entity.getSuccessFlag() == 1);
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    private QueryHistorySessionVO normalizeSessionTitle(QueryHistorySessionVO item) {
        String title = Objects.toString(item.getTitle(), "").trim();
        if (title.isBlank()) {
            item.setTitle("未命名会话");
            return item;
        }
        String oneLine = title.replaceAll("\\s+", " ").trim();
        item.setTitle(oneLine.length() > 60 ? oneLine.substring(0, 60) : oneLine);
        return item;
    }

    private ChartConfigVO parseChartConfig(String chartConfigJson) {
        String normalized = safe(chartConfigJson);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(normalized, ChartConfigVO.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private ErGraphSnapshotSummaryVO toSnapshotSummaryVO(ErGraphSnapshotEntity entity) {
        ErGraphSnapshotSummaryVO vo = new ErGraphSnapshotSummaryVO();
        vo.setId(entity.getId());
        vo.setConnectionId(entity.getConnectionId());
        vo.setDatabaseName(safe(entity.getDatabaseName()));
        vo.setSnapshotName(safe(entity.getSnapshotName()));
        vo.setTableCount(parseSelectedTables(entity.getSelectedTablesJson()).size());
        vo.setModelName(safe(entity.getModelName()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private ErGraphSnapshotVO toSnapshotDetailVO(ErGraphSnapshotEntity entity) {
        ErGraphSnapshotVO vo = new ErGraphSnapshotVO();
        vo.setId(entity.getId());
        vo.setConnectionId(entity.getConnectionId());
        vo.setDatabaseName(safe(entity.getDatabaseName()));
        vo.setSnapshotName(safe(entity.getSnapshotName()));
        vo.setSelectedTableNames(parseSelectedTables(entity.getSelectedTablesJson()));
        vo.setModelName(safe(entity.getModelName()));
        vo.setLayoutMode(safe(entity.getLayoutMode()));
        vo.setAiConfidenceThreshold(entity.getAiConfidenceThreshold());
        vo.setIncludeAiInference(entity.getIncludeAiInference() != null && entity.getIncludeAiInference() == 1);
        vo.setGraph(parseSnapshotGraph(entity.getGraphJson()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private List<String> normalizeSelectedTableNames(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String item : tableNames) {
            String normalized = safe(item);
            if (!normalized.isBlank()) {
                dedup.add(normalized);
            }
        }
        return new ArrayList<>(dedup);
    }

    private List<String> parseSelectedTables(String selectedTablesJson) {
        String jsonText = safe(selectedTablesJson);
        if (jsonText.isBlank()) {
            return List.of();
        }
        try {
            String[] values = objectMapper.readValue(jsonText, String[].class);
            return normalizeSelectedTableNames(Arrays.asList(values));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private ErGraphVO parseSnapshotGraph(String graphJson) {
        String normalized = safe(graphJson);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(normalized, ErGraphVO.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(500, "ER 图快照序列化失败: " + ex.getMessage());
        }
    }

    private Double normalizeSnapshotThreshold(Double threshold) {
        if (threshold == null || !Double.isFinite(threshold)) {
            return 0.6D;
        }
        return Math.max(0D, Math.min(1D, threshold));
    }

    private String resolveHistoryType(String historyType) {
        String normalized = safe(historyType).toUpperCase();
        if ("EXECUTE".equals(normalized)) {
            return "EXECUTE";
        }
        return "CHAT";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String sanitizePathSegment(String value) {
        String normalized = safe(value).replace("\\", "_").replace("/", "_");
        normalized = normalized.replace("..", "_");
        return normalized;
    }

    private String sanitizeFileName(String value) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            return "";
        }
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]+", "_");
        normalized = normalized.replaceAll("\\s+", "_");
        if (normalized.toLowerCase().endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }
}
