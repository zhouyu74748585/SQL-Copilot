package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.schema.*;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.service.AiService;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.ErDiagramService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class ErDiagramServiceImpl implements ErDiagramService {

    private static final int MAX_TABLE_COUNT = 30;

    private final ConnectionService connectionService;
    private final SchemaService schemaService;
    private final AiService aiService;

    public ErDiagramServiceImpl(ConnectionService connectionService,
                                SchemaService schemaService,
                                AiService aiService) {
        this.connectionService = connectionService;
        this.schemaService = schemaService;
        this.aiService = aiService;
    }

    @Override
    public ErGraphVO buildErGraph(ErGraphReq req) {
        if (req == null || req.getConnectionId() == null) {
            throw new BusinessException(400, "connectionId is required");
        }
        List<String> rawTableNames = req.getTableNames() == null ? List.of() : req.getTableNames();
        if (rawTableNames.isEmpty()) {
            throw new BusinessException(400, "tableNames is required");
        }
        if (rawTableNames.size() > MAX_TABLE_COUNT) {
            throw new BusinessException(400, "tableNames cannot exceed " + MAX_TABLE_COUNT);
        }

        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
        String databaseName = resolveTargetDatabaseName(connectionEntity, req.getDatabaseName());
        if (databaseName.isBlank()) {
            throw new BusinessException(400, "databaseName is required");
        }

        SchemaOverviewVO overview = schemaService.getOverview(req.getConnectionId(), databaseName);
        Map<String, SchemaOverviewVO.TableSummaryVO> overviewByLowerTable = new LinkedHashMap<>();
        for (SchemaOverviewVO.TableSummaryVO item : overview.getTableSummaries()) {
            String lower = normalize(item.getTableName());
            if (!lower.isBlank()) {
                overviewByLowerTable.put(lower, item);
            }
        }

        List<String> selectedTableNames = normalizeSelectedTableNames(rawTableNames, overviewByLowerTable);
        if (selectedTableNames.isEmpty()) {
            throw new BusinessException(400, "No valid tables selected");
        }

        Map<String, String> canonicalTableMap = new LinkedHashMap<>();
        for (String tableName : selectedTableNames) {
            canonicalTableMap.put(normalize(tableName), tableName);
        }

        List<ErTableNodeVO> tableNodes = buildTableNodes(req.getConnectionId(), databaseName, selectedTableNames, overviewByLowerTable);
        List<ErRelationVO> foreignKeyRelations = loadForeignKeyRelations(
            req.getConnectionId(),
            connectionEntity,
            databaseName,
            canonicalTableMap
        );

        List<ErRelationVO> aiRelations = List.of();
        ErAiInferenceStatusVO aiInferenceStatus = new ErAiInferenceStatusVO();
        boolean includeAiInference = req.getIncludeAiInference() == null || Boolean.TRUE.equals(req.getIncludeAiInference());
        aiInferenceStatus.setRequested(includeAiInference);
        if (includeAiInference) {
            try {
                ErAiInferenceReq inferenceReq = new ErAiInferenceReq();
                inferenceReq.setConnectionId(req.getConnectionId());
                inferenceReq.setDatabaseName(databaseName);
                inferenceReq.setModelName(req.getModelName());
                inferenceReq.setConfidenceThreshold(normalizeConfidenceThreshold(req.getAiConfidenceThreshold()));
                inferenceReq.setTables(tableNodes);
                inferenceReq.setForeignKeyRelations(foreignKeyRelations);

                ErAiInferenceResultVO inferenceResult = aiService.inferErRelations(inferenceReq);
                aiRelations = inferenceResult.getRelations() == null ? List.of() : inferenceResult.getRelations();
                aiInferenceStatus.setSuccess(Boolean.TRUE.equals(inferenceResult.getSuccess()));
                aiInferenceStatus.setMessage(safe(inferenceResult.getMessage()));
            } catch (Exception ex) {
                aiInferenceStatus.setSuccess(Boolean.FALSE);
                aiInferenceStatus.setMessage("AI inference failed: " + safe(ex.getMessage()));
                aiRelations = List.of();
            }
        } else {
            aiInferenceStatus.setSuccess(Boolean.FALSE);
            aiInferenceStatus.setMessage("AI inference skipped");
        }

        ErGraphVO vo = new ErGraphVO();
        vo.setConnectionId(req.getConnectionId());
        vo.setDatabaseName(databaseName);
        vo.setTables(tableNodes);
        vo.setForeignKeyRelations(foreignKeyRelations);
        vo.setAiRelations(aiRelations);
        vo.setAiInference(aiInferenceStatus);
        vo.setGeneratedAt(System.currentTimeMillis());
        return vo;
    }

    private List<String> normalizeSelectedTableNames(List<String> tableNames,
                                                     Map<String, SchemaOverviewVO.TableSummaryVO> overviewByLowerTable) {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String rawTableName : tableNames) {
            String raw = safe(rawTableName);
            if (raw.isBlank()) {
                continue;
            }
            String lower = normalize(raw);
            String canonical = raw;
            SchemaOverviewVO.TableSummaryVO summary = overviewByLowerTable.get(lower);
            if (summary != null && !safe(summary.getTableName()).isBlank()) {
                canonical = summary.getTableName();
            }
            unique.put(normalize(canonical), canonical);
        }
        return new ArrayList<>(unique.values());
    }

    private List<ErTableNodeVO> buildTableNodes(Long connectionId,
                                                String databaseName,
                                                List<String> selectedTableNames,
                                                Map<String, SchemaOverviewVO.TableSummaryVO> overviewByLowerTable) {
        List<ErTableNodeVO> nodes = new ArrayList<>();
        for (String tableName : selectedTableNames) {
            TableDetailVO tableDetail = schemaService.getTableDetail(connectionId, databaseName, tableName);
            ErTableNodeVO tableNode = new ErTableNodeVO();
            tableNode.setTableName(tableName);
            SchemaOverviewVO.TableSummaryVO summary = overviewByLowerTable.get(normalize(tableName));
            tableNode.setTableComment(summary == null ? "" : safe(summary.getTableComment()));
            List<ErColumnNodeVO> columns = new ArrayList<>();
            List<TableDetailVO.ColumnDetailVO> details = tableDetail.getColumns() == null ? List.of() : tableDetail.getColumns();
            for (TableDetailVO.ColumnDetailVO column : details) {
                ErColumnNodeVO item = new ErColumnNodeVO();
                item.setColumnName(safe(column.getColumnName()));
                item.setDataType(safe(column.getDataType()));
                item.setPrimaryKey(Boolean.TRUE.equals(column.getPrimaryKey()));
                item.setIndexed(Boolean.TRUE.equals(column.getIndexed()));
                item.setNullable(Boolean.TRUE.equals(column.getNullable()));
                columns.add(item);
            }
            tableNode.setColumns(columns);
            nodes.add(tableNode);
        }
        nodes.sort(Comparator.comparing(ErTableNodeVO::getTableName, String.CASE_INSENSITIVE_ORDER));
        return nodes;
    }

    private List<ErRelationVO> loadForeignKeyRelations(Long connectionId,
                                                       ConnectionEntity connectionEntity,
                                                       String databaseName,
                                                       Map<String, String> canonicalTableMap) {
        String dbType = safe(connectionEntity.getDbType()).toUpperCase(Locale.ROOT);
        Set<String> selectedLowerTables = canonicalTableMap.keySet();
        Map<String, ErRelationVO> dedup = new LinkedHashMap<>();
        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            applyDatabaseContext(connection, dbType, databaseName);
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = resolveCatalog(connection, dbType, databaseName);
            String schemaPattern = resolveSchemaPattern(dbType, databaseName);
            for (String lowerTableName : selectedLowerTables) {
                String tableName = canonicalTableMap.get(lowerTableName);
                try (ResultSet rs = metaData.getImportedKeys(catalog, schemaPattern, tableName)) {
                    while (rs.next()) {
                        String sourceTable = safe(rs.getString("FKTABLE_NAME"));
                        String sourceColumn = safe(rs.getString("FKCOLUMN_NAME"));
                        String targetTable = safe(rs.getString("PKTABLE_NAME"));
                        String targetColumn = safe(rs.getString("PKCOLUMN_NAME"));
                        String sourceLower = normalize(sourceTable);
                        String targetLower = normalize(targetTable);
                        if (sourceLower.isBlank() || targetLower.isBlank()) {
                            continue;
                        }
                        if (!selectedLowerTables.contains(sourceLower) || !selectedLowerTables.contains(targetLower)) {
                            continue;
                        }
                        if (sourceColumn.isBlank() || targetColumn.isBlank()) {
                            continue;
                        }
                        ErRelationVO relation = new ErRelationVO();
                        relation.setSourceTable(canonicalTableMap.getOrDefault(sourceLower, sourceTable));
                        relation.setSourceColumn(sourceColumn);
                        relation.setTargetTable(canonicalTableMap.getOrDefault(targetLower, targetTable));
                        relation.setTargetColumn(targetColumn);
                        relation.setRelationType("FK");
                        relation.setConfidence(1.0D);
                        relation.setReason("database foreign key metadata");
                        dedup.put(relationKey(relation), relation);
                    }
                }
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "load foreign keys failed: " + ex.getMessage());
        }
        List<ErRelationVO> relations = new ArrayList<>(dedup.values());
        relations.sort(Comparator
            .comparing(ErRelationVO::getSourceTable, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getTargetTable, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getSourceColumn, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getTargetColumn, String.CASE_INSENSITIVE_ORDER));
        return relations;
    }

    private void applyDatabaseContext(Connection connection, String dbType, String targetDatabaseName) throws SQLException {
        if (targetDatabaseName.isBlank()) {
            return;
        }
        if ("MYSQL".equals(dbType) || "POSTGRESQL".equals(dbType)) {
            connection.setCatalog(targetDatabaseName);
        }
        if ("SQLSERVER".equals(dbType) || "ORACLE".equals(dbType)) {
            connection.setSchema(targetDatabaseName);
        }
    }

    private String resolveCatalog(Connection connection, String dbType, String targetDatabaseName) throws SQLException {
        if ("MYSQL".equals(dbType) || "POSTGRESQL".equals(dbType)) {
            if (!targetDatabaseName.isBlank()) {
                return targetDatabaseName;
            }
            return safe(connection.getCatalog());
        }
        return null;
    }

    private String resolveSchemaPattern(String dbType, String targetDatabaseName) {
        if (("SQLSERVER".equals(dbType) || "ORACLE".equals(dbType)) && !targetDatabaseName.isBlank()) {
            return targetDatabaseName;
        }
        return null;
    }

    private String relationKey(ErRelationVO relation) {
        return normalize(relation.getSourceTable()) + "|"
            + normalize(relation.getSourceColumn()) + "|"
            + normalize(relation.getTargetTable()) + "|"
            + normalize(relation.getTargetColumn());
    }

    private Double normalizeConfidenceThreshold(Double rawValue) {
        if (rawValue == null || !Double.isFinite(rawValue)) {
            return 0.6D;
        }
        return Math.max(0D, Math.min(1D, rawValue));
    }

    private String resolveTargetDatabaseName(ConnectionEntity connectionEntity, String requestedDatabaseName) {
        String requested = safe(requestedDatabaseName);
        if (!requested.isBlank()) {
            return requested;
        }
        String configured = safe(connectionEntity.getDatabaseName());
        if (!configured.isBlank()) {
            return configured;
        }
        return extractDatabaseNameFromHost(connectionEntity.getHost());
    }

    private String extractDatabaseNameFromHost(String host) {
        String value = safe(host);
        if (value.isBlank()) {
            return "";
        }
        int marker = value.indexOf("://");
        if (marker >= 0) {
            value = value.substring(marker + 3);
        }
        int queryIndex = value.indexOf("?");
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        int slashIndex = value.indexOf("/");
        if (slashIndex >= 0 && slashIndex < value.length() - 1) {
            return value.substring(slashIndex + 1).trim();
        }
        return "";
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return Objects.toString(value, "").trim();
    }
}

