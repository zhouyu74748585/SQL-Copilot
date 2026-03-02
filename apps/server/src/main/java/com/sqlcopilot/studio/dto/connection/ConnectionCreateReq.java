package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 新建数据库连接请求对象。 */
@Data
public class ConnectionCreateReq {

    /** 连接名称。 */
    @NotBlank
    private String name;

    /** 数据库类型：MYSQL/POSTGRESQL/SQLITE/SQLSERVER/ORACLE。 */
    @NotBlank
    private String dbType;

    /** 数据库主机地址。 */
    private String host;

    /** 数据库端口。 */
    private Integer port;

    /** 数据库名称或 SQLite 文件路径。 */
    private String databaseName;

    /** 登录用户名。 */
    private String username;

    /** 登录密码。 */
    private String password;

    /** 认证方式：PASSWORD/SSH_TUNNEL。 */
    private String authType;

    /** 环境标识：DEV/TEST/PROD。 */
    @NotBlank
    private String env;

    /** 是否只读：true 表示仅允许查询。 */
    @NotNull
    private Boolean readOnly;

    /** 是否启用 SSH 隧道。 */
    @NotNull
    private Boolean sshEnabled;

    /** SSH 主机地址。 */
    private String sshHost;

    /** SSH 端口。 */
    private Integer sshPort;

    /** SSH 用户名。 */
    private String sshUser;
}
