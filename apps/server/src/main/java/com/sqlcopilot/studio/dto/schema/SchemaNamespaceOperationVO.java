package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/** 库/命名空间操作响应对象。 */
@Data
public class SchemaNamespaceOperationVO {

    /** 是否成功。 */
    private boolean success;

    /** 响应消息。 */
    private String message;

    /** 原名称。 */
    private String sourceNamespaceName;

    /** 新名称。 */
    private String targetNamespaceName;

    /** 执行 SQL。 */
    private String executedSql;

    public static SchemaNamespaceOperationVO success(String message,
                                                     String sourceNamespaceName,
                                                     String targetNamespaceName,
                                                     String executedSql) {
        SchemaNamespaceOperationVO vo = new SchemaNamespaceOperationVO();
        vo.setSuccess(true);
        vo.setMessage(message);
        vo.setSourceNamespaceName(sourceNamespaceName);
        vo.setTargetNamespaceName(targetNamespaceName);
        vo.setExecutedSql(executedSql);
        return vo;
    }
}
