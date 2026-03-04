package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.sql.*;
import com.sqlcopilot.studio.entity.AuditLogEntity;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.mapper.AuditLogMapper;
import com.sqlcopilot.studio.mapper.QueryHistoryMapper;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SqlService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
import com.sqlcopilot.studio.util.BusinessException;
import com.sqlcopilot.studio.util.ResultSetConverter;
import com.sqlcopilot.studio.util.SqlClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SqlServiceImpl implements SqlService {

    private static final long ACK_TOKEN_TTL_MS = 5 * 60 * 1000;
    private static final Logger log = LoggerFactory.getLogger(SqlServiceImpl.class);

    private final ConnectionService connectionService;
    private final QueryHistoryMapper queryHistoryMapper;
    private final AuditLogMapper auditLogMapper;
    private final RagIngestionService ragIngestionService;
    private final ConcurrentHashMap<String, RiskAckPayload> riskAckStore = new ConcurrentHashMap<>();

    public SqlServiceImpl(ConnectionService connectionService,
                          QueryHistoryMapper queryHistoryMapper,
                          AuditLogMapper auditLogMapper,
                          RagIngestionService ragIngestionService) {
        this.connectionService = connectionService;
        this.queryHistoryMapper = queryHistoryMapper;
        this.auditLogMapper = auditLogMapper;
        this.ragIngestionService = ragIngestionService;
    }

    @Override
    public ExplainVO explain(ExplainReq req) {
        String sql = req.getSqlText().trim();
        ensureSingleStatement(sql);

        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity.getDatabaseName(), req.getDatabaseName());
        String explainSql = buildExplainSql(connectionEntity.getDbType(), sql);
        log.info("[SQL-EXPLAIN] connectionId={}, databaseName={}, sql={}",
            req.getConnectionId(), targetDatabaseName, sql);
        log.info("[SQL-EXPLAIN] connectionId={}, databaseName={}, explainSql={}",
            req.getConnectionId(), targetDatabaseName, explainSql);

        ExplainVO vo = new ExplainVO();
        try (Connection connection = connectionService.openTargetConnection(req.getConnectionId())) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(explainSql)) {
                vo.setRows(ResultSetConverter.readRows(resultSet, 200));
                vo.setSummary("Explain 分析完成");
                return vo;
            }
        } catch (Exception ex) {
            throw new BusinessException(500, "Explain 执行失败: " + ex.getMessage());
        }
    }

    @Override
    public RiskEvaluateVO evaluateRisk(RiskEvaluateReq req) {
        log.info("[SQL-RISK] connectionId={}, sql={}", req.getConnectionId(), req.getSqlText());
        ConnectionEntity connection = connectionService.getConnectionEntity(req.getConnectionId());
        List<RiskItemVO> items = evaluateRiskItems(req.getSqlText(), connection.getDbType());
        String riskLevel = decideRiskLevel(items);
        boolean confirmRequired = requiresRiskConfirm(connection.getEnv(), riskLevel);

        RiskEvaluateVO vo = new RiskEvaluateVO();
        vo.setRiskLevel(riskLevel);
        vo.setRiskItems(items);
        vo.setConfirmRequired(confirmRequired);
        if (confirmRequired) {
            vo.setConfirmReason(buildConfirmReason(connection.getEnv(), riskLevel));
            String token = UUID.randomUUID().toString();
            riskAckStore.put(token, new RiskAckPayload(SqlClassifier.digest(req.getSqlText()),
                System.currentTimeMillis() + ACK_TOKEN_TTL_MS));
            vo.setRiskAckToken(token);
        }
        return vo;
    }

    @Override
    public SqlExecuteVO execute(SqlExecuteReq req) {
        String sql = req.getSqlText().trim();
        ensureSingleStatement(sql);
        ConnectionEntity connection = connectionService.getConnectionEntity(req.getConnectionId());
        String targetDatabaseName = resolveTargetDatabaseName(connection.getDatabaseName(), req.getDatabaseName());
        log.info("[SQL-EXECUTE] connectionId={}, sessionId={}, databaseName={}, sql={}",
            req.getConnectionId(), req.getSessionId(), targetDatabaseName, sql);

        List<RiskItemVO> items = evaluateRiskItems(sql, connection.getDbType());
        String riskLevel = decideRiskLevel(items);

        // 关键拦截：只读连接禁止 DML。
        if ((connection.getReadOnly() != null && connection.getReadOnly() == 1) && SqlClassifier.isDml(sql)) {
            throw new BusinessException(403, "当前连接为只读模式，禁止执行写入 SQL");
        }

        if (requiresRiskConfirm(connection.getEnv(), riskLevel)) {
            validateAckToken(sql, req.getRiskAckToken(), riskLevel, connection.getEnv());
        }

        long start = System.currentTimeMillis();
        SqlExecuteVO result = new SqlExecuteVO();
        try (Connection jdbcConnection = connectionService.openTargetConnection(req.getConnectionId())) {
            applyDatabaseContext(jdbcConnection, connection.getDbType(), targetDatabaseName);
            try (Statement statement = jdbcConnection.createStatement()) {
                if (SqlClassifier.isQuery(sql)) {
                    log.info("[SQL-EXECUTE] connectionId={}, databaseName={}, querySql={}",
                        req.getConnectionId(), targetDatabaseName, sql);
                    try (ResultSet resultSet = statement.executeQuery(sql)) {
                        result.setColumns(ResultSetConverter.readColumns(resultSet.getMetaData()));
                        result.setRows(ResultSetConverter.readRows(resultSet, 500));
                        result.setAffectedRows(result.getRows().size());
                    }
                } else {
                    log.info("[SQL-EXECUTE] connectionId={}, databaseName={}, dmlSql={}",
                        req.getConnectionId(), targetDatabaseName, sql);
                    int affected = statement.executeUpdate(sql);
                    result.setAffectedRows(affected);
                    result.setRows(new ArrayList<>());
                    result.setColumns(new ArrayList<>());
                }
            }
            result.setSuccess(Boolean.TRUE);
            result.setMessage("执行成功");
        } catch (Exception ex) {
            throw new BusinessException(500, "SQL 执行失败: " + ex.getMessage());
        } finally {
            result.setExecutionMs(System.currentTimeMillis() - start);
            appendHistory(req, result);
            appendAudit(req, riskLevel, "EXECUTE");
        }
        return result;
    }

    private String buildExplainSql(String dbType, String sql) {
        String upper = dbType == null ? "" : dbType.toUpperCase(Locale.ROOT);
        if ("SQLITE".equals(upper)) {
            return "EXPLAIN QUERY PLAN " + sql;
        }
        return "EXPLAIN " + sql;
    }

    /**
     * 关键操作：SQL 执行链路显式设置数据库上下文，避免未配置默认库时出现 No database selected。
     */
    private void applyDatabaseContext(Connection connection, String dbType, String targetDatabaseName) throws SQLException {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if (targetDatabaseName.isBlank()) {
            return;
        }
        if ("MYSQL".equals(type) || "POSTGRESQL".equals(type)) {
            connection.setCatalog(targetDatabaseName);
        }
        if ("SQLSERVER".equals(type) || "ORACLE".equals(type)) {
            connection.setSchema(targetDatabaseName);
        }
    }

    private String resolveTargetDatabaseName(String configuredDatabaseName, String requestedDatabaseName) {
        String requested = normalize(requestedDatabaseName);
        if (!requested.isBlank()) {
            return requested;
        }
        return normalize(configuredDatabaseName);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private void validateAckToken(String sql, String riskAckToken, String riskLevel, String env) {
        if (riskAckToken == null || riskAckToken.isBlank()) {
            throw new BusinessException(400, "当前风险级别需确认令牌后才能执行");
        }
        RiskAckPayload payload = riskAckStore.get(riskAckToken);
        if (payload == null || payload.expiredAt < System.currentTimeMillis()) {
            throw new BusinessException(400, "riskAckToken 无效或已过期");
        }
        if (!payload.sqlDigest.equals(SqlClassifier.digest(sql))) {
            throw new BusinessException(400, "riskAckToken 与 SQL 不匹配");
        }
        riskAckStore.remove(riskAckToken);
        log.info("[SQL-RISK-ACK] env={}, riskLevel={}, digest={}", normalizeEnv(env), riskLevel, SqlClassifier.digest(sql));
    }

    private List<RiskItemVO> evaluateRiskItems(String sql, String dbType) {
        String normalized = SqlClassifier.normalize(sql);
        List<RiskItemVO> items = new ArrayList<>();

        if ((normalized.startsWith("update") || normalized.startsWith("delete"))
            && !SqlClassifier.hasWhereForUpdateDelete(sql)) {
            items.add(risk("NO_WHERE_DML", "update/delete 无 where 条件", "HIGH"));
        }

        if (normalized.startsWith("select") && !normalized.contains(" where ")) {
            items.add(risk("FULL_SCAN", "查询缺少 where 条件，可能触发全表扫描", "MEDIUM"));
        }

        if (normalized.startsWith("select") && !hasPaginationClause(normalized, dbType)) {
            items.add(risk("NO_PAGINATION", "查询缺少分页条件，建议分页执行", "MEDIUM"));
        }

        if (normalized.contains(" for update ")) {
            items.add(risk("LOCK_RISK", "检测到 FOR UPDATE，存在锁表风险", "HIGH"));
        }

        if (normalized.startsWith("begin") || normalized.contains(" commit") || normalized.contains(" rollback")) {
            items.add(risk("TX_RISK", "检测到事务控制语句，请确认事务范围", "MEDIUM"));
        }

        return items;
    }

    private String decideRiskLevel(List<RiskItemVO> items) {
        boolean hasHigh = items.stream().anyMatch(item -> "HIGH".equals(item.getLevel()));
        if (hasHigh) {
            return "HIGH";
        }
        boolean hasMedium = items.stream().anyMatch(item -> "MEDIUM".equals(item.getLevel()));
        return hasMedium ? "MEDIUM" : "LOW";
    }

    private boolean requiresRiskConfirm(String env, String riskLevel) {
        String normalizedLevel = normalize(riskLevel).toUpperCase(Locale.ROOT);
        String normalizedEnv = normalizeEnv(env);
        if ("PROD".equals(normalizedEnv)) {
            return "MEDIUM".equals(normalizedLevel) || "HIGH".equals(normalizedLevel);
        }
        return "HIGH".equals(normalizedLevel);
    }

    private String buildConfirmReason(String env, String riskLevel) {
        String normalizedLevel = normalize(riskLevel).toUpperCase(Locale.ROOT);
        if ("PROD".equals(normalizeEnv(env))) {
            return "PROD_MEDIUM_PLUS";
        }
        if ("HIGH".equals(normalizedLevel)) {
            return "HIGH_RISK";
        }
        return "NONE";
    }

    private String normalizeEnv(String env) {
        String value = normalize(env).toUpperCase(Locale.ROOT);
        if ("PROD".equals(value) || "TEST".equals(value) || "DEV".equals(value)) {
            return value;
        }
        return "DEV";
    }

    private boolean hasPaginationClause(String normalizedSql, String dbType) {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if ("MYSQL".equals(type) || "POSTGRESQL".equals(type) || "SQLITE".equals(type)) {
            return hasMySqlLikePagination(normalizedSql);
        }
        if ("SQLSERVER".equals(type)) {
            return hasSqlServerPagination(normalizedSql);
        }
        if ("ORACLE".equals(type)) {
            return hasOraclePagination(normalizedSql);
        }
        return hasGenericPagination(normalizedSql);
    }

    private boolean hasMySqlLikePagination(String normalizedSql) {
        return normalizedSql.contains(" limit ")
            || normalizedSql.contains(" fetch first ")
            || normalizedSql.contains(" fetch next ");
    }

    private boolean hasSqlServerPagination(String normalizedSql) {
        return normalizedSql.startsWith("select top ")
            || normalizedSql.startsWith("select top(")
            || normalizedSql.startsWith("select distinct top ")
            || normalizedSql.startsWith("select distinct top(")
            || (normalizedSql.contains(" offset ") && normalizedSql.contains(" fetch "));
    }

    private boolean hasOraclePagination(String normalizedSql) {
        return normalizedSql.contains(" rownum ")
            || normalizedSql.contains(" fetch first ")
            || normalizedSql.contains(" fetch next ")
            || (normalizedSql.contains(" offset ") && normalizedSql.contains(" fetch "));
    }

    private boolean hasGenericPagination(String normalizedSql) {
        return normalizedSql.contains(" limit ")
            || normalizedSql.contains(" top ")
            || normalizedSql.contains(" rownum ")
            || normalizedSql.contains(" fetch first ")
            || normalizedSql.contains(" fetch next ")
            || (normalizedSql.contains(" offset ") && normalizedSql.contains(" fetch "));
    }

    private RiskItemVO risk(String code, String description, String level) {
        RiskItemVO item = new RiskItemVO();
        item.setRuleCode(code);
        item.setDescription(description);
        item.setLevel(level);
        return item;
    }

    private void appendHistory(SqlExecuteReq req, SqlExecuteVO result) {
        QueryHistoryEntity history = new QueryHistoryEntity();
        history.setConnectionId(req.getConnectionId());
        history.setSessionId(req.getSessionId());
        history.setPromptText(null);
        history.setSqlText(req.getSqlText());
        history.setExecutionMs(result.getExecutionMs());
        history.setSuccessFlag(Boolean.TRUE.equals(result.getSuccess()) ? 1 : 0);
        history.setCreatedAt(System.currentTimeMillis());
        queryHistoryMapper.insert(history);
        // 关键策略：仅对执行成功的 SQL 做向量化写入，避免未成功语句污染检索样本。
        if (history.getSuccessFlag() != null && history.getSuccessFlag() == 1) {
            ragIngestionService.ingestSqlHistory(history);
        }
    }

    private void appendAudit(SqlExecuteReq req, String riskLevel, String action) {
        AuditLogEntity audit = new AuditLogEntity();
        audit.setConnectionId(req.getConnectionId());
        audit.setSessionId(req.getSessionId());
        audit.setRiskLevel(riskLevel);
        audit.setSqlDigest(SqlClassifier.digest(req.getSqlText()));
        audit.setOperatorName(req.getOperatorName() == null ? "system" : req.getOperatorName());
        audit.setAction(action);
        audit.setCreatedAt(System.currentTimeMillis());
        auditLogMapper.insert(audit);
    }

    private void ensureSingleStatement(String sql) {
        String trimmed = sql.trim();
        int semicolonCount = (int) trimmed.chars().filter(ch -> ch == ';').count();
        if (semicolonCount > 1 || (semicolonCount == 1 && !trimmed.endsWith(";"))) {
            throw new BusinessException(400, "仅支持单条 SQL 执行");
        }
    }

    private record RiskAckPayload(String sqlDigest, long expiredAt) {
    }
}
