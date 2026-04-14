package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.schema.TableDataCommitReq;
import com.sqlcopilot.studio.dto.schema.TableDataCommitVO;
import com.sqlcopilot.studio.dto.schema.TableDataPageReq;
import com.sqlcopilot.studio.dto.schema.TableDataPageVO;
import com.sqlcopilot.studio.dto.schema.TableDetailVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.repository.TableDataJdbcRepository;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.TableDataService;
import com.sqlcopilot.studio.support.SchemaContextSupport;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 表数据浏览与编辑服务实现。
 */
@Service
public class TableDataServiceImpl implements TableDataService {

    private static final Set<String> SUPPORTED_FILTER_OPERATORS = Set.of(
        "EQ", "NE", "GT", "GTE", "LT", "LTE", "LIKE", "IS_NULL", "IS_NOT_NULL"
    );
    private static final Set<String> SUPPORTED_SORT_DIRECTIONS = Set.of("ASC", "DESC");

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final int MAX_PAGE_SIZE = 20000;

    private final ConnectionService connectionService;
    private final SchemaService schemaService;
    private final TableDataJdbcRepository tableDataJdbcRepository;

    public TableDataServiceImpl(ConnectionService connectionService,
                                SchemaService schemaService,
                                TableDataJdbcRepository tableDataJdbcRepository) {
        this.connectionService = connectionService;
        this.schemaService = schemaService;
        this.tableDataJdbcRepository = tableDataJdbcRepository;
    }

    @Override
    public TableDataPageVO page(TableDataPageReq req) {
        String tableName = normalize(req.getTableName());
        String databaseName = normalize(req.getDatabaseName());
        String objectType = normalizeObjectType(req.getObjectType());
        if (tableName.isBlank()) {
            throw new BusinessException(400, "表名不能为空");
        }
        if (databaseName.isBlank()) {
            throw new BusinessException(400, "数据库名称不能为空");
        }
        if (!"tables".equals(objectType) && !"views".equals(objectType)) {
            throw new BusinessException(400, "仅支持表或视图数据浏览");
        }

        int pageNo = req.getPageNo() == null || req.getPageNo() <= 0 ? DEFAULT_PAGE_NO : req.getPageNo();
        int pageSize = req.getPageSize() == null || req.getPageSize() <= 0
            ? DEFAULT_PAGE_SIZE
            : Math.min(req.getPageSize(), MAX_PAGE_SIZE);

        TableDetailVO tableDetail = schemaService.getTableDetail(req.getConnectionId(), databaseName, tableName);
        if (tableDetail == null || tableDetail.getColumns() == null || tableDetail.getColumns().isEmpty()) {
            throw new BusinessException(404, "未找到可浏览的表结构: " + tableName);
        }

        List<String> allColumns = tableDetail.getColumns().stream()
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
        if (allColumns.isEmpty()) {
            throw new BusinessException(404, "表字段为空，无法浏览数据: " + tableName);
        }
        Set<String> allowedColumnsLower = allColumns.stream()
            .map(item -> item.toLowerCase(Locale.ROOT))
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Map<String, String> columnNameMapping = allColumns.stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.toLowerCase(Locale.ROOT), item), LinkedHashMap::putAll);

        List<String> primaryKeyColumns = tableDetail.getColumns().stream()
            .filter(item -> Boolean.TRUE.equals(item.getPrimaryKey()))
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();

        List<TableDataPageReq.FilterItem> filters = sanitizeAndValidateFilters(
            req.getFilters(),
            allowedColumnsLower,
            columnNameMapping,
            allColumns
        );
        List<TableDataPageReq.SortItem> sorts = sanitizeAndValidateSorts(
            req.getSorts(),
            allowedColumnsLower,
            columnNameMapping,
            allColumns
        );
        List<String> defaultOrderColumns = primaryKeyColumns.isEmpty() ? List.of(allColumns.get(0)) : primaryKeyColumns;

        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
        TableDataPageVO vo = new TableDataPageVO();
        vo.setTableName(tableName);
        vo.setPageNo(pageNo);
        vo.setPageSize(pageSize);
        vo.setPrimaryKeyColumns(primaryKeyColumns);
        boolean viewReadOnly = "views".equals(objectType);
        vo.setEditable(!viewReadOnly && !primaryKeyColumns.isEmpty() && !isReadOnlyConnection(connectionEntity));
        if (viewReadOnly) {
            vo.setReadOnlyReason("视图只支持只读浏览");
        } else if (isReadOnlyConnection(connectionEntity)) {
            vo.setReadOnlyReason("当前连接为只读模式");
        } else if (primaryKeyColumns.isEmpty()) {
            vo.setReadOnlyReason("该表未识别到主键，暂不支持编辑与删除");
        } else {
            vo.setReadOnlyReason("");
        }
        vo.setColumns(buildColumns(tableDetail));

        String dbType = connectionEntity.getDbType();
        try (Connection connection = connectionService.openTargetConnection(req.getConnectionId(), databaseName)) {
            applyDatabaseContext(connection, dbType, databaseName);
            int fetchSize = pageSize + 1;
            List<TableDataPageVO.RowVO> rows = tableDataJdbcRepository.queryPage(
                connection,
                dbType,
                databaseName,
                tableName,
                allColumns,
                defaultOrderColumns,
                sorts,
                allowedColumnsLower,
                filters,
                pageNo,
                pageSize,
                fetchSize
            );
            boolean hasNext = rows.size() > pageSize;
            if (hasNext) {
                rows = rows.subList(0, pageSize);
            }
            vo.setHasNext(hasNext);
            vo.setRows(rows);
            return vo;
        } catch (SQLException ex) {
            throw new BusinessException(500, "分页查询失败: " + ex.getMessage());
        }
    }

    @Override
    public TableDataCommitVO commit(TableDataCommitReq req) {
        String tableName = normalize(req.getTableName());
        String databaseName = normalize(req.getDatabaseName());
        String objectType = normalizeObjectType(req.getObjectType());
        if (tableName.isBlank()) {
            throw new BusinessException(400, "表名不能为空");
        }
        if (databaseName.isBlank()) {
            throw new BusinessException(400, "数据库名称不能为空");
        }
        if (!"tables".equals(objectType)) {
            throw new BusinessException(400, "视图只支持只读浏览，禁止提交数据变更");
        }

        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
        if (isReadOnlyConnection(connectionEntity)) {
            throw new BusinessException(403, "当前连接为只读模式，禁止提交数据变更");
        }

        TableDetailVO tableDetail = schemaService.getTableDetail(req.getConnectionId(), databaseName, tableName);
        if (tableDetail == null || tableDetail.getColumns() == null || tableDetail.getColumns().isEmpty()) {
            throw new BusinessException(404, "未找到可编辑的表结构: " + tableName);
        }

        Map<String, String> columnNameMapping = buildColumnNameMapping(tableDetail);
        if (columnNameMapping.isEmpty()) {
            throw new BusinessException(400, "表字段为空，无法提交变更");
        }
        List<String> primaryKeyColumns = tableDetail.getColumns().stream()
            .filter(item -> Boolean.TRUE.equals(item.getPrimaryKey()))
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
        if (primaryKeyColumns.isEmpty()) {
            throw new BusinessException(400, "该表未识别到主键，暂不支持提交编辑/删除");
        }

        List<LinkedHashMap<String, Object>> inserts = parseInsertRows(req.getInserts(), columnNameMapping);
        List<UpdateRowData> updates = parseUpdateRows(req.getUpdates(), columnNameMapping, primaryKeyColumns);
        List<LinkedHashMap<String, Object>> deletes = parseDeleteRows(req.getDeletes(), columnNameMapping, primaryKeyColumns);

        String dbType = connectionEntity.getDbType();
        int insertedCount = 0;
        int updatedCount = 0;
        int deletedCount = 0;

        try (Connection connection = connectionService.openTargetConnection(req.getConnectionId(), databaseName)) {
            applyDatabaseContext(connection, dbType, databaseName);
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // 关键操作：删除 -> 更新 -> 新增，单事务全成全败。
                for (LinkedHashMap<String, Object> deletePk : deletes) {
                    int affected = tableDataJdbcRepository.deleteByPrimaryKey(
                        connection,
                        dbType,
                        databaseName,
                        tableName,
                        primaryKeyColumns,
                        deletePk
                    );
                    if (affected != 1) {
                        throw new BusinessException(409, "删除失败，目标数据已变化或不存在");
                    }
                    deletedCount += affected;
                }

                for (UpdateRowData updateRow : updates) {
                    int affected = tableDataJdbcRepository.updateByPrimaryKey(
                        connection,
                        dbType,
                        databaseName,
                        tableName,
                        updateRow.updateValues(),
                        primaryKeyColumns,
                        updateRow.primaryKeyValues()
                    );
                    if (affected != 1) {
                        throw new BusinessException(409, "更新失败，目标数据已变化或不存在");
                    }
                    updatedCount += affected;
                }

                for (LinkedHashMap<String, Object> insertValues : inserts) {
                    int affected = tableDataJdbcRepository.insertRow(connection, dbType, databaseName, tableName, insertValues);
                    if (affected != 1) {
                        throw new BusinessException(500, "新增失败，未写入任何数据");
                    }
                    insertedCount += affected;
                }

                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                if (ex instanceof BusinessException businessException) {
                    throw businessException;
                }
                throw new BusinessException(500, "提交失败: " + ex.getMessage());
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "提交失败: " + ex.getMessage());
        }

        return TableDataCommitVO.success("提交成功", insertedCount, updatedCount, deletedCount);
    }

    private List<TableDataPageVO.ColumnVO> buildColumns(TableDetailVO tableDetail) {
        List<TableDataPageVO.ColumnVO> columns = new ArrayList<>();
        for (TableDetailVO.ColumnDetailVO columnDetail : tableDetail.getColumns()) {
            TableDataPageVO.ColumnVO columnVO = new TableDataPageVO.ColumnVO();
            columnVO.setColumnName(columnDetail.getColumnName());
            columnVO.setColumnType(columnDetail.getDataType());
            columnVO.setColumnComment(columnDetail.getColumnComment());
            columnVO.setNullable(columnDetail.getNullable());
            columnVO.setPrimaryKey(columnDetail.getPrimaryKey());
            columns.add(columnVO);
        }
        return columns;
    }

    private List<TableDataPageReq.FilterItem> sanitizeAndValidateFilters(List<TableDataPageReq.FilterItem> filters,
                                                                         Set<String> allowedColumnsLower,
                                                                         Map<String, String> columnNameMapping,
                                                                         List<String> allColumns) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        List<TableDataPageReq.FilterItem> result = new ArrayList<>();
        for (TableDataPageReq.FilterItem filter : filters) {
            if (filter == null) {
                continue;
            }
            String columnName = normalize(filter.getColumnName());
            if (columnName.isBlank()) {
                continue;
            }
            String columnKey = columnName.toLowerCase(Locale.ROOT);
            if (!allowedColumnsLower.contains(columnKey)) {
                throw new BusinessException(400, "过滤字段不存在: " + columnName + "，可选字段: " + String.join(",", allColumns));
            }
            String actualColumnName = columnNameMapping.get(columnKey);
            if (actualColumnName == null) {
                throw new BusinessException(400, "过滤字段不存在: " + columnName);
            }
            String operator = normalize(filter.getOperator()).toUpperCase(Locale.ROOT);
            if (!SUPPORTED_FILTER_OPERATORS.contains(operator)) {
                throw new BusinessException(400, "不支持的过滤操作符: " + operator);
            }
            TableDataPageReq.FilterItem next = new TableDataPageReq.FilterItem();
            next.setColumnName(actualColumnName);
            next.setOperator(operator);
            next.setValue(filter.getValue());
            result.add(next);
        }
        return result;
    }

    private List<TableDataPageReq.SortItem> sanitizeAndValidateSorts(List<TableDataPageReq.SortItem> sorts,
                                                                     Set<String> allowedColumnsLower,
                                                                     Map<String, String> columnNameMapping,
                                                                     List<String> allColumns) {
        if (sorts == null || sorts.isEmpty()) {
            return List.of();
        }
        List<TableDataPageReq.SortItem> result = new ArrayList<>();
        for (TableDataPageReq.SortItem sort : sorts) {
            if (sort == null) {
                continue;
            }
            String columnName = normalize(sort.getColumnName());
            if (columnName.isBlank()) {
                continue;
            }
            String columnKey = columnName.toLowerCase(Locale.ROOT);
            if (!allowedColumnsLower.contains(columnKey)) {
                throw new BusinessException(400, "排序字段不存在: " + columnName + "，可选字段: " + String.join(",", allColumns));
            }
            String actualColumnName = columnNameMapping.get(columnKey);
            if (actualColumnName == null) {
                throw new BusinessException(400, "排序字段不存在: " + columnName);
            }
            String direction = normalize(sort.getDirection()).toUpperCase(Locale.ROOT);
            if (!SUPPORTED_SORT_DIRECTIONS.contains(direction)) {
                throw new BusinessException(400, "不支持的排序方向: " + direction);
            }
            TableDataPageReq.SortItem next = new TableDataPageReq.SortItem();
            next.setColumnName(actualColumnName);
            next.setDirection(direction);
            result.add(next);
        }
        return result;
    }

    private List<LinkedHashMap<String, Object>> parseInsertRows(List<TableDataCommitReq.InsertRowReq> inserts,
                                                                 Map<String, String> columnNameMapping) {
        if (inserts == null || inserts.isEmpty()) {
            return List.of();
        }
        List<LinkedHashMap<String, Object>> result = new ArrayList<>();
        for (TableDataCommitReq.InsertRowReq insert : inserts) {
            if (insert == null || insert.getCells() == null || insert.getCells().isEmpty()) {
                continue;
            }
            LinkedHashMap<String, Object> values = convertCellsToMap(insert.getCells(), columnNameMapping, false);
            if (!values.isEmpty()) {
                result.add(values);
            }
        }
        return result;
    }

    private List<UpdateRowData> parseUpdateRows(List<TableDataCommitReq.UpdateRowReq> updates,
                                                Map<String, String> columnNameMapping,
                                                List<String> primaryKeyColumns) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }
        Set<String> primaryKeyLower = primaryKeyColumns.stream()
            .map(item -> item.toLowerCase(Locale.ROOT))
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        List<UpdateRowData> result = new ArrayList<>();
        for (TableDataCommitReq.UpdateRowReq update : updates) {
            if (update == null) {
                continue;
            }
            LinkedHashMap<String, Object> primaryKeyValues = convertCellsToMap(update.getPrimaryKeyValues(), columnNameMapping, true);
            ensurePrimaryKeyComplete(primaryKeyValues, primaryKeyColumns);

            LinkedHashMap<String, Object> updateValues = convertCellsToMap(update.getCells(), columnNameMapping, true);
            for (String columnName : new ArrayList<>(updateValues.keySet())) {
                if (primaryKeyLower.contains(columnName.toLowerCase(Locale.ROOT))) {
                    throw new BusinessException(400, "不支持修改主键字段: " + columnName);
                }
            }
            if (updateValues.isEmpty()) {
                throw new BusinessException(400, "更新字段不能为空");
            }
            result.add(new UpdateRowData(primaryKeyValues, updateValues));
        }
        return result;
    }

    private List<LinkedHashMap<String, Object>> parseDeleteRows(List<TableDataCommitReq.DeleteRowReq> deletes,
                                                                 Map<String, String> columnNameMapping,
                                                                 List<String> primaryKeyColumns) {
        if (deletes == null || deletes.isEmpty()) {
            return List.of();
        }
        List<LinkedHashMap<String, Object>> result = new ArrayList<>();
        for (TableDataCommitReq.DeleteRowReq delete : deletes) {
            if (delete == null) {
                continue;
            }
            LinkedHashMap<String, Object> primaryKeyValues = convertCellsToMap(delete.getPrimaryKeyValues(), columnNameMapping, true);
            ensurePrimaryKeyComplete(primaryKeyValues, primaryKeyColumns);
            result.add(primaryKeyValues);
        }
        return result;
    }

    private LinkedHashMap<String, Object> convertCellsToMap(List<TableDataCommitReq.CellValueReq> cells,
                                                             Map<String, String> columnNameMapping,
                                                             boolean skipNullCellList) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if (cells == null || cells.isEmpty()) {
            if (skipNullCellList) {
                return values;
            }
            throw new BusinessException(400, "字段值不能为空");
        }
        for (TableDataCommitReq.CellValueReq cell : cells) {
            if (cell == null) {
                continue;
            }
            String inputColumn = normalize(cell.getColumnName());
            if (inputColumn.isBlank()) {
                continue;
            }
            String actualColumn = columnNameMapping.get(inputColumn.toLowerCase(Locale.ROOT));
            if (actualColumn == null) {
                throw new BusinessException(400, "字段不存在: " + inputColumn);
            }
            values.put(actualColumn, cell.getCellValue());
        }
        return values;
    }

    private void ensurePrimaryKeyComplete(LinkedHashMap<String, Object> primaryKeyValues, List<String> primaryKeyColumns) {
        for (String primaryKeyColumn : primaryKeyColumns) {
            if (!primaryKeyValues.containsKey(primaryKeyColumn)) {
                throw new BusinessException(400, "主键值缺失: " + primaryKeyColumn);
            }
        }
    }

    private Map<String, String> buildColumnNameMapping(TableDetailVO tableDetail) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (TableDetailVO.ColumnDetailVO column : tableDetail.getColumns()) {
            String name = normalize(column.getColumnName());
            if (!name.isBlank()) {
                mapping.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return mapping;
    }

    private boolean isReadOnlyConnection(ConnectionEntity connectionEntity) {
        return connectionEntity.getReadOnly() != null && connectionEntity.getReadOnly() == 1;
    }

    /**
     * 关键操作：按数据库类型切换连接上下文，避免跨库读取或写入。
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

    private String normalize(String value) {
        return Objects.toString(value, "").trim();
    }

    private String normalizeObjectType(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "tables" : normalized;
    }

    private record UpdateRowData(LinkedHashMap<String, Object> primaryKeyValues,
                                 LinkedHashMap<String, Object> updateValues) {
    }
}
