package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

@Data
public class TableOperationVO {

    private boolean success;
    private String message;
    private String executedSql;

    public static TableOperationVO success(String message, String executedSql) {
        TableOperationVO vo = new TableOperationVO();
        vo.setSuccess(true);
        vo.setMessage(message);
        vo.setExecutedSql(executedSql);
        return vo;
    }

    public static TableOperationVO failure(String message) {
        TableOperationVO vo = new TableOperationVO();
        vo.setSuccess(false);
        vo.setMessage(message);
        return vo;
    }
}
