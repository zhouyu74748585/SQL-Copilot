package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.sql.*;
import com.sqlcopilot.studio.entity.AuditLogEntity;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.mapper.AuditLogMapper;
import com.sqlcopilot.studio.mapper.QueryHistoryMapper;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.SqlService;
import com.sqlcopilot.studio.service.TokenEstimatorService;
import com.sqlcopilot.studio.support.SchemaContextSupport;
import com.sqlcopilot.studio.util.BusinessException;
import com.sqlcopilot.studio.util.ResultSetConverter;
import com.sqlcopilot.studio.util.SqlClassifier;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SqlServiceImpl implements SqlService {

    private static final long ACK_TOKEN_TTL_MS = 5 * 60 * 1000;
    private static final int DEFAULT_QUERY_MAX_ROWS = 5000;
    private static final int ABSOLUTE_QUERY_MAX_ROWS = 5000;
    private static final long LARGE_FULL_SCAN_ROW_THRESHOLD = 10_000L;
    private static final Executor JDBC_ABORT_EXECUTOR = Runnable::run;
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\blimit\\s+(\\d+)\\b");
    private static final Pattern LIMIT_OFFSET_PATTERN = Pattern.compile("\\blimit\\s+(\\d+)\\s+offset\\s+\\d+\\b");
    private static final Pattern LIMIT_COMMA_PATTERN = Pattern.compile("\\blimit\\s+\\d+\\s*,\\s*(\\d+)\\b");
    private static final Pattern FETCH_PATTERN = Pattern.compile("\\bfetch\\s+(?:first|next)\\s+(\\d+)\\s+rows?\\s+only\\b");
    private static final Pattern TOP_PATTERN = Pattern.compile("^select\\s+(?:distinct\\s+)?top\\s*\\(?\\s*(\\d+)\\s*\\)?\\b");
    private static final Logger log = LoggerFactory.getLogger(SqlServiceImpl.class);

    private final ConnectionService connectionService;
    private final SchemaService schemaService;
    private final QueryHistoryMapper queryHistoryMapper;
    private final AuditLogMapper auditLogMapper;
    private final TokenEstimatorService tokenEstimatorService;
    private final ConcurrentHashMap<String, RiskAckPayload> riskAckStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RunningSqlExecution> runningExecutionStore = new ConcurrentHashMap<>();

    public SqlServiceImpl(ConnectionService connectionService,
                          SchemaService schemaService,
                          QueryHistoryMapper queryHistoryMapper,
                          AuditLogMapper auditLogMapper,
                          TokenEstimatorService tokenEstimatorService) {
        this.connectionService = connectionService;
        this.schemaService = schemaService;
        this.queryHistoryMapper = queryHistoryMapper;
        this.auditLogMapper = auditLogMapper;
        this.tokenEstimatorService = tokenEstimatorService;
    }

    @Override
    public ExplainVO explain(ExplainReq req) {
        String sql = req.getSqlText().trim();
        ensureSingleStatement(sql);

        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity.getDatabaseName(), req.getDatabaseName());
        String dbType = normalize(connectionEntity.getDbType()).toUpperCase(Locale.ROOT);
        String explainSql = buildExplainSql(connectionEntity.getDbType(), sql);
        log.info("[SQL-EXPLAIN] connectionId={}, databaseName={}, sql={}",
            req.getConnectionId(), targetDatabaseName, sql);
        log.info("[SQL-EXPLAIN] connectionId={}, databaseName={}, explainSql={}",
            req.getConnectionId(), targetDatabaseName, explainSql);

        ExplainVO vo = new ExplainVO();
        try (Connection connection = connectionService.openTargetConnection(req.getConnectionId())) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            if ("SQLSERVER".equals(dbType)) {
                return explainSqlServer(sql, connection, vo);
            }
            if ("ORACLE".equals(dbType)) {
                return explainOracle(sql, connection, vo);
            }
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

    private ExplainVO explainSqlServer(String sql, Connection connection, ExplainVO vo) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SHOWPLAN_ALL ON");
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                vo.setRows(ResultSetConverter.readRows(resultSet, 200));
                vo.setSummary("SQL Server 执行计划分析完成");
                return vo;
            } finally {
                statement.execute("SET SHOWPLAN_ALL OFF");
            }
        }
    }

    private ExplainVO explainOracle(String sql, Connection connection, ExplainVO vo) throws SQLException {
        String statementId = buildOracleExplainStatementId();
        try (Statement statement = connection.createStatement()) {
            statement.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' FOR " + sql);
            try (ResultSet resultSet = statement.executeQuery(
                "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, '" + statementId + "', 'TYPICAL'))")) {
                vo.setRows(ResultSetConverter.readRows(resultSet, 400));
                vo.setSummary("Oracle 执行计划分析完成");
                return vo;
            } finally {
                try {
                    statement.executeUpdate("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = '" + statementId + "'");
                } catch (SQLException cleanupEx) {
                    log.warn("[SQL-EXPLAIN] cleanup oracle plan table failed. statementId={}, reason={}",
                        statementId, cleanupEx.getMessage());
                }
            }
        }
    }

    private String buildOracleExplainStatementId() {
        String token = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        return "SQLCOPILOT_" + token.substring(0, 18);
    }

    @Override
    public RiskEvaluateVO evaluateRisk(RiskEvaluateReq req) {
        log.info("[SQL-RISK] connectionId={}, sql={}", req.getConnectionId(), req.getSqlText());
        ConnectionEntity connection = connectionService.getConnectionEntity(req.getConnectionId());
        String targetDatabaseName = resolveTargetDatabaseName(connection.getDatabaseName(), req.getDatabaseName());
        List<RiskItemVO> items = evaluateRiskItems(
            req.getSqlText(),
            connection.getDbType(),
            req.getConnectionId(),
            targetDatabaseName
        );
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

        List<RiskItemVO> items = evaluateRiskItems(sql, connection.getDbType(), req.getConnectionId(), targetDatabaseName);
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
                registerRunningExecution(req, targetDatabaseName, jdbcConnection, statement);
                if (SqlClassifier.isQuery(sql)) {
                    int maxRows = normalizeQueryMaxRows(req.getMaxRows());
                    log.info("[SQL-EXECUTE] connectionId={}, databaseName={}, querySql={}",
                        req.getConnectionId(), targetDatabaseName, sql);
                    try (ResultSet resultSet = statement.executeQuery(sql)) {
                        result.setColumns(ResultSetConverter.readColumns(resultSet.getMetaData()));
                        List<QueryRowVO> rows = ResultSetConverter.readRows(resultSet, maxRows + 1);
                        boolean truncated = rows.size() > maxRows;
                        if (truncated) {
                            rows = new ArrayList<>(rows.subList(0, maxRows));
                        }
                        result.setRows(rows);
                        result.setTruncated(truncated);
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
            if (isExecutionInterrupted(req.getSessionId(), ex)) {
                result.setSuccess(Boolean.FALSE);
                result.setMessage("SQL 执行已中断");
                result.setRows(new ArrayList<>());
                result.setColumns(new ArrayList<>());
                throw new BusinessException(499, "SQL 执行已中断");
            }
            result.setSuccess(Boolean.FALSE);
            result.setMessage("SQL 执行失败: " + ex.getMessage());
            throw new BusinessException(500, result.getMessage());
        } finally {
            clearRunningExecution(req.getSessionId());
            result.setExecutionMs(System.currentTimeMillis() - start);
            appendHistory(req, result, targetDatabaseName);
            appendAudit(req, riskLevel, "EXECUTE");
        }
        return result;
    }

    @Override
    public SqlInterruptVO interrupt(SqlInterruptReq req) {
        SqlInterruptVO vo = new SqlInterruptVO();
        RunningSqlExecution running = runningExecutionStore.get(req.getSessionId());
        if (running == null || !req.getConnectionId().equals(running.connectionId())) {
            vo.setInterrupted(Boolean.FALSE);
            vo.setMessage("当前会话没有正在执行的 SQL");
            return vo;
        }
        log.info("[SQL-INTERRUPT] connectionId={}, sessionId={}, databaseName={}",
            req.getConnectionId(), req.getSessionId(), running.databaseName());
        running.markInterrupted();
        cancelStatementQuietly(running);
        abortConnectionQuietly(running);
        vo.setInterrupted(Boolean.TRUE);
        vo.setMessage("已发送 SQL 中断信号");
        return vo;
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
        SchemaContextSupport.SchemaContext context = SchemaContextSupport.parse(type, targetDatabaseName);
        if (context.rawContext().isBlank()) {
            return;
        }
        if ("MYSQL".equals(type)) {
            connection.setCatalog(context.databaseName());
        }
        if ("POSTGRESQL".equals(type)) {
            if (!context.databaseName().isBlank()) {
                connection.setCatalog(context.databaseName());
            }
            if (context.hasNamespace()) {
                connection.setSchema(context.namespaceName());
            }
        }
        if ("SQLSERVER".equals(type)) {
            if (!context.databaseName().isBlank()) {
                connection.setCatalog(context.databaseName());
            }
            if (context.hasNamespace()) {
                connection.setSchema(context.namespaceName());
            }
        }
        if ("ORACLE".equals(type) && context.hasNamespace()) {
            connection.setSchema(context.namespaceName());
        }
    }

    private String resolveTargetDatabaseName(String configuredDatabaseName, String requestedDatabaseName) {
        String requested = normalize(requestedDatabaseName);
        if (!requested.isBlank()) {
            return requested;
        }
        return normalize(configuredDatabaseName);
    }

    /**
     * 关键操作：查询结果允许前端声明返回上限，但服务端仍统一做最大值保护，避免一次性拉取过大结果集。
     */
    private int normalizeQueryMaxRows(Integer requestedMaxRows) {
        if (requestedMaxRows == null || requestedMaxRows <= 0) {
            return DEFAULT_QUERY_MAX_ROWS;
        }
        return Math.min(requestedMaxRows, ABSOLUTE_QUERY_MAX_ROWS);
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

    private List<RiskItemVO> evaluateRiskItems(String sql, String dbType, Long connectionId, String targetDatabaseName) {
        String normalized = SqlClassifier.normalize(sql);
        List<RiskItemVO> items = new ArrayList<>();

        if ((normalized.startsWith("update") || normalized.startsWith("delete"))
            && !SqlClassifier.hasWhereForUpdateDelete(sql)) {
            items.add(risk("NO_WHERE_DML", "update/delete 无 where 条件", "HIGH"));
        }

        if (isSelectWithoutEffectiveFilter(sql)) {
            items.add(risk("FULL_SCAN", "查询缺少有效过滤条件，可能触发全表扫描", "MEDIUM"));
            raiseRiskForLargeTableFullScan(items, sql, connectionId, targetDatabaseName);
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

    /**
     * 关键操作：无条件全表查询命中时，结合表行数统计二次判断，大表直接提升为 HIGH 风险。
     */
    private void raiseRiskForLargeTableFullScan(List<RiskItemVO> items,
                                                String sql,
                                                Long connectionId,
                                                String targetDatabaseName) {
        if (connectionId == null || normalize(targetDatabaseName).isBlank()) {
            return;
        }
        OptionalLong rowLimit = resolveQueryRowLimit(sql);
        if (rowLimit.isPresent() && rowLimit.getAsLong() <= LARGE_FULL_SCAN_ROW_THRESHOLD) {
            log.info("[SQL-RISK] connectionId={}, databaseName={}, skipLargeFullScanUpgrade=true, rowLimit={}",
                connectionId, targetDatabaseName, rowLimit.getAsLong());
            return;
        }
        List<String> relatedTables = extractRelatedTables(sql);
        if (relatedTables.isEmpty()) {
            return;
        }
        Optional<LargeFullScanRiskContext> riskContext = resolveLargeFullScanRiskContext(
            connectionId,
            targetDatabaseName,
            relatedTables
        );
        if (riskContext.isEmpty()) {
            return;
        }
        LargeFullScanRiskContext context = riskContext.get();
        items.add(risk(
            "LARGE_FULL_SCAN",
            "查询缺少有效过滤条件，且涉及大表 " + context.tableName()
                + "（约 " + context.rowEstimate() + " 行），风险提升为 HIGH",
            "HIGH"
        ));
        log.info("[SQL-RISK] connectionId={}, databaseName={}, largeFullScanTable={}, rowEstimate={}",
            connectionId, targetDatabaseName, context.tableName(), context.rowEstimate());
    }

    /**
     * 关键操作：无条件查询若已被 LIMIT/TOP/FETCH 明确限制在 10000 行以内，则不再提升为高风险。
     */
    private OptionalLong resolveQueryRowLimit(String sql) {
        String normalized = SqlClassifier.normalize(sql);
        OptionalLong mysqlLikeLimit = matchLong(LIMIT_OFFSET_PATTERN, normalized);
        if (mysqlLikeLimit.isPresent()) {
            return mysqlLikeLimit;
        }
        OptionalLong mysqlCommaLimit = matchLong(LIMIT_COMMA_PATTERN, normalized);
        if (mysqlCommaLimit.isPresent()) {
            return mysqlCommaLimit;
        }
        OptionalLong plainLimit = matchLong(LIMIT_PATTERN, normalized);
        if (plainLimit.isPresent()) {
            return plainLimit;
        }
        OptionalLong fetchLimit = matchLong(FETCH_PATTERN, normalized);
        if (fetchLimit.isPresent()) {
            return fetchLimit;
        }
        return matchLong(TOP_PATTERN, normalized);
    }

    /**
     * 关键操作：存在 where 关键字但条件整体恒真时，仍按“等同无过滤”处理。
     */
    private boolean isSelectWithoutEffectiveFilter(String sql) {
        String normalized = SqlClassifier.normalize(sql);
        if (!normalized.startsWith("select")) {
            return false;
        }
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements == null || statements.getStatements() == null || statements.getStatements().size() != 1) {
                return !normalized.contains(" where ");
            }
            net.sf.jsqlparser.statement.Statement parsedStatement = statements.getStatements().get(0);
            if (!(parsedStatement instanceof Select select)) {
                return !normalized.contains(" where ");
            }
            return isSelectWithoutEffectiveFilter(select);
        } catch (Exception ex) {
            log.debug("[SQL-RISK] 解析 where 条件失败，回退关键字判断. reason={}", ex.getMessage());
            return !normalized.contains(" where ");
        }
    }

    private boolean isSelectWithoutEffectiveFilter(Select select) {
        PlainSelect plainSelect = select.getPlainSelect();
        if (plainSelect != null) {
            return isWhereMissingOrTautology(plainSelect.getWhere());
        }
        SetOperationList setOperationList = select.getSetOperationList();
        if (setOperationList != null && setOperationList.getSelects() != null && !setOperationList.getSelects().isEmpty()) {
            return setOperationList.getSelects().stream().allMatch(this::isSelectWithoutEffectiveFilter);
        }
        return false;
    }

    private boolean isWhereMissingOrTautology(Expression where) {
        return where == null || isTautologyExpression(where);
    }

    private boolean isTautologyExpression(Expression expression) {
        if (expression == null) {
            return true;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return isTautologyExpression(parenthesis.getExpression());
        }
        if (expression instanceof AndExpression andExpression) {
            return isTautologyExpression(andExpression.getLeftExpression())
                && isTautologyExpression(andExpression.getRightExpression());
        }
        if (expression instanceof OrExpression orExpression) {
            return isTautologyExpression(orExpression.getLeftExpression())
                || isTautologyExpression(orExpression.getRightExpression());
        }
        if (expression instanceof EqualsTo equalsTo) {
            return areEquivalentOperands(equalsTo.getLeftExpression(), equalsTo.getRightExpression());
        }
        if (expression instanceof NotEqualsTo notEqualsTo) {
            Optional<String> leftLiteral = resolveLiteralValue(notEqualsTo.getLeftExpression());
            Optional<String> rightLiteral = resolveLiteralValue(notEqualsTo.getRightExpression());
            return leftLiteral.isPresent() && rightLiteral.isPresent() && !leftLiteral.get().equals(rightLiteral.get());
        }
        return normalize(expression.toString()).equalsIgnoreCase("true");
    }

    private boolean areEquivalentOperands(Expression left, Expression right) {
        if (left instanceof Column leftColumn && right instanceof Column rightColumn) {
            return normalize(leftColumn.getFullyQualifiedName()).equalsIgnoreCase(normalize(rightColumn.getFullyQualifiedName()));
        }
        Optional<String> leftLiteral = resolveLiteralValue(left);
        Optional<String> rightLiteral = resolveLiteralValue(right);
        if (leftLiteral.isPresent() && rightLiteral.isPresent()) {
            return leftLiteral.get().equals(rightLiteral.get());
        }
        return normalize(left.toString()).equalsIgnoreCase(normalize(right.toString()));
    }

    private Optional<String> resolveLiteralValue(Expression expression) {
        if (expression instanceof Parenthesis parenthesis) {
            return resolveLiteralValue(parenthesis.getExpression());
        }
        if (expression instanceof SignedExpression signedExpression) {
            Optional<String> nested = resolveLiteralValue(signedExpression.getExpression());
            if (nested.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(signedExpression.getSign() + nested.get());
        }
        if (expression instanceof LongValue longValue) {
            return Optional.of(String.valueOf(longValue.getValue()));
        }
        if (expression instanceof DoubleValue doubleValue) {
            return Optional.of(String.valueOf(doubleValue.getValue()));
        }
        if (expression instanceof StringValue stringValue) {
            return Optional.of(stringValue.getValue());
        }
        if (expression instanceof HexValue hexValue) {
            return Optional.of(hexValue.getValue());
        }
        return Optional.empty();
    }

    private OptionalLong matchLong(Pattern pattern, String normalizedSql) {
        Matcher matcher = pattern.matcher(normalizedSql);
        if (!matcher.find()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(matcher.group(1)));
        } catch (Exception ex) {
            return OptionalLong.empty();
        }
    }

    private Optional<LargeFullScanRiskContext> resolveLargeFullScanRiskContext(Long connectionId,
                                                                               String targetDatabaseName,
                                                                               List<String> relatedTables) {
        Map<String, Long> statsByTable = new LinkedHashMap<>();
        try {
            List<com.sqlcopilot.studio.dto.schema.SchemaTableStatsVO.TableStatVO> tableStats =
                Optional.ofNullable(schemaService.getTableStats(connectionId, targetDatabaseName).getTableStats())
                    .orElse(List.of());
            tableStats.forEach(item -> {
                String normalizedTableName = normalizeTableNameForStatsMatch(item.getTableName());
                if (!normalizedTableName.isBlank()) {
                    statsByTable.put(normalizedTableName, Math.max(0L, item.getRowEstimate() == null ? 0L : item.getRowEstimate()));
                }
            });
        } catch (Exception ex) {
            log.debug("[SQL-RISK] 读取表统计失败，将回退到 schema 概览. connectionId={}, databaseName={}, reason={}",
                connectionId, targetDatabaseName, ex.getMessage());
        }
        if (statsByTable.isEmpty()) {
            try {
                List<com.sqlcopilot.studio.dto.schema.SchemaOverviewVO.TableSummaryVO> tableSummaries =
                    Optional.ofNullable(schemaService.getOverview(connectionId, targetDatabaseName).getTableSummaries())
                        .orElse(List.of());
                tableSummaries.forEach(item -> {
                    String normalizedTableName = normalizeTableNameForStatsMatch(item.getTableName());
                    if (!normalizedTableName.isBlank()) {
                        statsByTable.put(normalizedTableName, Math.max(0L, item.getRowEstimate() == null ? 0L : item.getRowEstimate()));
                    }
                });
            } catch (Exception ex) {
                log.debug("[SQL-RISK] 读取 schema 概览失败，跳过大表全扫升级. connectionId={}, databaseName={}, reason={}",
                    connectionId, targetDatabaseName, ex.getMessage());
            }
        }
        LargeFullScanRiskContext candidate = null;
        for (String relatedTable : relatedTables) {
            String tableKey = normalizeTableNameForStatsMatch(relatedTable);
            long rowEstimate = statsByTable.getOrDefault(tableKey, 0L);
            if (rowEstimate < LARGE_FULL_SCAN_ROW_THRESHOLD) {
                continue;
            }
            if (candidate == null || rowEstimate > candidate.rowEstimate()) {
                candidate = new LargeFullScanRiskContext(relatedTable, rowEstimate);
            }
        }
        return Optional.ofNullable(candidate);
    }

    private List<String> extractRelatedTables(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements == null || statements.getStatements() == null || statements.getStatements().size() != 1) {
                return List.of();
            }
            net.sf.jsqlparser.statement.Statement parsedStatement = statements.getStatements().get(0);
            TablesNamesFinder finder = new TablesNamesFinder();
            return finder.getTableList(parsedStatement).stream()
                .map(this::normalizeTableNameForStatsMatch)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
        } catch (Exception ex) {
            log.debug("[SQL-RISK] 解析关联表失败，将跳过大表全扫升级. reason={}", ex.getMessage());
            return List.of();
        }
    }

    private String normalizeTableNameForStatsMatch(String tableName) {
        String normalized = normalize(tableName)
            .replace("`", "")
            .replace("\"", "")
            .replace("[", "")
            .replace("]", "");
        if (normalized.isBlank()) {
            return "";
        }
        String[] parts = normalized.split("\\.");
        return parts[parts.length - 1].trim().toLowerCase(Locale.ROOT);
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

    /**
     * 关键操作：执行 SQL 前登记 JDBC Statement/Connection，便于前端点击停止时真正中断数据库侧执行。
     */
    private void registerRunningExecution(SqlExecuteReq req,
                                          String targetDatabaseName,
                                          Connection jdbcConnection,
                                          Statement statement) {
        runningExecutionStore.put(req.getSessionId(), new RunningSqlExecution(
            req.getConnectionId(),
            req.getSessionId(),
            normalize(targetDatabaseName),
            jdbcConnection,
            statement
        ));
    }

    private void clearRunningExecution(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        runningExecutionStore.remove(sessionId);
    }

    private boolean isExecutionInterrupted(String sessionId, Exception ex) {
        RunningSqlExecution running = sessionId == null ? null : runningExecutionStore.get(sessionId);
        if (running != null && running.interrupted()) {
            return true;
        }
        String message = normalize(ex == null ? "" : ex.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("cancel")
            || message.contains("interrupt")
            || message.contains("closed")
            || message.contains("statement is closed")
            || message.contains("connection is closed");
    }

    private void cancelStatementQuietly(RunningSqlExecution running) {
        try {
            running.statement().cancel();
        } catch (Exception ex) {
            log.debug("[SQL-INTERRUPT] Statement.cancel 失败, sessionId={}, reason={}", running.sessionId(), ex.getMessage());
        }
    }

    private void abortConnectionQuietly(RunningSqlExecution running) {
        try {
            running.connection().abort(JDBC_ABORT_EXECUTOR);
            return;
        } catch (Exception ex) {
            log.debug("[SQL-INTERRUPT] Connection.abort 不可用, sessionId={}, reason={}", running.sessionId(), ex.getMessage());
        }
        try {
            running.connection().close();
        } catch (Exception ex) {
            log.debug("[SQL-INTERRUPT] Connection.close 失败, sessionId={}, reason={}", running.sessionId(), ex.getMessage());
        }
    }

    private void appendHistory(SqlExecuteReq req, SqlExecuteVO result, String targetDatabaseName) {
        boolean memoryEnabled = resolveExecuteMemoryEnabled(req);
        QueryHistoryEntity history = new QueryHistoryEntity();
        history.setConnectionId(req.getConnectionId());
        history.setSessionId(req.getSessionId());
        history.setPromptText(null);
        history.setSqlText(req.getSqlText());
        history.setHistoryType("EXECUTE");
        history.setActionType("execute");
        history.setAssistantContent(result.getMessage());
        history.setDatabaseName(normalize(targetDatabaseName));
        history.setChartConfigJson(null);
        history.setChartImageCacheKey(null);
        history.setTurnContentTokens(tokenEstimatorService.estimateTurnContentTokens(
            null,
            req.getSqlText(),
            result.getMessage(),
            null
        ));
        history.setTokenEstimateSource(TokenEstimatorService.TOKEN_SOURCE_BACKEND_ESTIMATOR);
        history.setTokenEstimateVersion(TokenEstimatorService.TOKEN_ESTIMATE_VERSION);
        history.setTokenEstimateScope(TokenEstimatorService.TOKEN_SCOPE_TURN_CONTENT);
        history.setMemoryEnabled(memoryEnabled ? 1 : 0);
        history.setExecutionMs(result.getExecutionMs());
        history.setSuccessFlag(Boolean.TRUE.equals(result.getSuccess()) ? 1 : 0);
        history.setCreatedAt(System.currentTimeMillis());
        queryHistoryMapper.insert(history);
        // 单一长期记忆池方案下，执行历史仅保留在 query_history，不再进入 SQL 历史向量池。
    }

    private boolean resolveExecuteMemoryEnabled(SqlExecuteReq req) {
        if (req.getMemoryEnabled() != null) {
            return Boolean.TRUE.equals(req.getMemoryEnabled());
        }
        // 向后兼容：旧客户端未传开关时保持历史行为（默认开启）。
        return true;
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

    private static final class RunningSqlExecution {

        private final Long connectionId;
        private final String sessionId;
        private final String databaseName;
        private final Connection connection;
        private final Statement statement;
        private volatile boolean interrupted;

        private RunningSqlExecution(Long connectionId,
                                    String sessionId,
                                    String databaseName,
                                    Connection connection,
                                    Statement statement) {
            this.connectionId = connectionId;
            this.sessionId = sessionId;
            this.databaseName = databaseName;
            this.connection = connection;
            this.statement = statement;
            this.interrupted = false;
        }

        private Long connectionId() {
            return connectionId;
        }

        private String sessionId() {
            return sessionId;
        }

        private String databaseName() {
            return databaseName;
        }

        private Connection connection() {
            return connection;
        }

        private Statement statement() {
            return statement;
        }

        private boolean interrupted() {
            return interrupted;
        }

        private void markInterrupted() {
            this.interrupted = true;
        }
    }

    private record LargeFullScanRiskContext(String tableName, long rowEstimate) {
    }
}
