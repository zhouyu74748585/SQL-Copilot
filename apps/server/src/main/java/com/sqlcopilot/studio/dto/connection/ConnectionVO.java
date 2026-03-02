package com.sqlcopilot.studio.dto.connection;

import lombok.Data;

/** 数据库连接信息响应对象。 */
@Data
public class ConnectionVO {

    /** 连接主键 ID。 */
    private Long id;

    /** 连接名称。 */
    private String name;

    /** 数据库类型。 */
    private String dbType;

    /** 数据库主机地址。 */
    private String host;

    /** 数据库端口。 */
    private Integer port;

    /** 数据库名称或路径。 */
    private String databaseName;

    /** 登录用户名。 */
    private String username;

    /** 环境标识。 */
    private String env;

    /** 是否只读。 */
    private Boolean readOnly;

    /** 是否启用 SSH 隧道。 */
    private Boolean sshEnabled;

    /** SSH 主机地址。 */
    private String sshHost;

    /** SSH 端口。 */
    private Integer sshPort;

    /** SSH 用户名。 */
    private String sshUser;

    /** 最近一次连通性状态。 */
    private String lastTestStatus;

    /** 最近一次连通性结果描述。 */
    private String lastTestMessage;

    /** 风险策略摘要。 */
    private String riskPolicySummary;
}
