package com.sqlcopilot.studio.util;

import com.sqlcopilot.studio.dto.sql.ColumnMetaVO;
import com.sqlcopilot.studio.dto.sql.QueryCellVO;
import com.sqlcopilot.studio.dto.sql.QueryRowVO;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ResultSetConverter {

    private ResultSetConverter() {
    }

    public static List<ColumnMetaVO> readColumns(ResultSetMetaData metaData) throws SQLException {
        List<ColumnMetaVO> columns = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            ColumnMetaVO column = new ColumnMetaVO();
            column.setColumnName(metaData.getColumnLabel(i));
            column.setColumnType(metaData.getColumnTypeName(i));
            columns.add(column);
        }
        return columns;
    }

    public static List<QueryRowVO> readRows(ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<QueryRowVO> rows = new ArrayList<>();
        int count = 0;
        while (resultSet.next() && count < maxRows) {
            QueryRowVO row = new QueryRowVO();
            List<QueryCellVO> cells = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                QueryCellVO cell = new QueryCellVO();
                cell.setColumnName(metaData.getColumnLabel(i));
                Object value = resultSet.getObject(i);
                cell.setCellValue(value == null ? null : String.valueOf(value));
                cells.add(cell);
            }
            row.setCells(cells);
            rows.add(row);
            count++;
        }
        return rows;
    }
}
