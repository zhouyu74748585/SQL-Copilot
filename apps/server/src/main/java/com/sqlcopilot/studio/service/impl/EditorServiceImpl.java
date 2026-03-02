package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.editor.ExportReq;
import com.sqlcopilot.studio.dto.editor.ExportResultVO;
import com.sqlcopilot.studio.dto.editor.QueryHistoryVO;
import com.sqlcopilot.studio.dto.editor.SaveQueryHistoryReq;
import com.sqlcopilot.studio.dto.sql.QueryCellVO;
import com.sqlcopilot.studio.dto.sql.QueryRowVO;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.mapper.QueryHistoryMapper;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.EditorService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
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
import java.util.ArrayList;
import java.util.List;

@Service
public class EditorServiceImpl implements EditorService {

    private final QueryHistoryMapper queryHistoryMapper;
    private final ConnectionService connectionService;
    private final RagIngestionService ragIngestionService;

    public EditorServiceImpl(QueryHistoryMapper queryHistoryMapper,
                             ConnectionService connectionService,
                             RagIngestionService ragIngestionService) {
        this.queryHistoryMapper = queryHistoryMapper;
        this.connectionService = connectionService;
        this.ragIngestionService = ragIngestionService;
    }

    @Override
    public List<QueryHistoryVO> listHistory(Long connectionId, Integer limit) {
        int actualLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 200);
        return queryHistoryMapper.listByConnection(connectionId, actualLimit).stream().map(this::toHistoryVO).toList();
    }

    @Override
    public void saveHistory(SaveQueryHistoryReq req) {
        QueryHistoryEntity entity = new QueryHistoryEntity();
        entity.setConnectionId(req.getConnectionId());
        entity.setSessionId(req.getSessionId());
        entity.setPromptText(req.getPromptText());
        entity.setSqlText(req.getSqlText());
        entity.setExecutionMs(req.getExecutionMs());
        entity.setSuccessFlag(Boolean.TRUE.equals(req.getSuccess()) ? 1 : 0);
        entity.setCreatedAt(System.currentTimeMillis());
        queryHistoryMapper.insert(entity);
        ragIngestionService.ingestSqlHistory(entity);
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
        vo.setExecutionMs(entity.getExecutionMs());
        vo.setSuccess(entity.getSuccessFlag() == 1);
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
