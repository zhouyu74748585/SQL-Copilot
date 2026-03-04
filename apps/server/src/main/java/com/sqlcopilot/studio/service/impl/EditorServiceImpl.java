package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.ai.ChartConfigVO;
import com.sqlcopilot.studio.dto.editor.*;
import com.sqlcopilot.studio.dto.sql.QueryCellVO;
import com.sqlcopilot.studio.dto.sql.QueryRowVO;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.mapper.QueryHistoryMapper;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.EditorService;
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
    private final ConnectionService connectionService;
    private final ObjectMapper objectMapper;

    public EditorServiceImpl(QueryHistoryMapper queryHistoryMapper,
                             ConnectionService connectionService,
                             ObjectMapper objectMapper) {
        this.queryHistoryMapper = queryHistoryMapper;
        this.connectionService = connectionService;
        this.objectMapper = objectMapper;
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
    public void removeHistorySession(DeleteHistorySessionReq req) {
        String normalizedSessionId = Objects.toString(req.getSessionId(), "").trim();
        if (normalizedSessionId.isBlank()) {
            throw new BusinessException(400, "会话 ID 不能为空");
        }
        // 关键操作：按连接与会话 ID 一次性删除整组历史记录，避免残留碎片数据。
        queryHistoryMapper.deleteBySession(req.getConnectionId(), normalizedSessionId);
    }

    @Override
    public void saveHistory(SaveQueryHistoryReq req) {
        QueryHistoryEntity entity = new QueryHistoryEntity();
        entity.setConnectionId(req.getConnectionId());
        entity.setSessionId(req.getSessionId());
        entity.setPromptText(req.getPromptText());
        entity.setSqlText(req.getSqlText());
        entity.setHistoryType(resolveHistoryType(req.getHistoryType()));
        entity.setActionType(safe(req.getActionType()));
        entity.setAssistantContent(safe(req.getAssistantContent()));
        entity.setDatabaseName(safe(req.getDatabaseName()));
        entity.setChartConfigJson(safe(req.getChartConfigJson()));
        entity.setChartImageCacheKey(safe(req.getChartImageCacheKey()));
        entity.setExecutionMs(req.getExecutionMs());
        entity.setSuccessFlag(Boolean.TRUE.equals(req.getSuccess()) ? 1 : 0);
        entity.setCreatedAt(System.currentTimeMillis());
        queryHistoryMapper.insert(entity);
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
