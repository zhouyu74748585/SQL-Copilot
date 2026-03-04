package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

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

    /** 勾选展示的数据库列表（仅 MySQL/PostgreSQL/SQLServer 生效）。 */
    private List<String> selectedDatabases;

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

    /** SSH 认证方式：SSH_PASSWORD/SSH_KEY_PATH/SSH_KEY_TEXT。 */
    private String sshAuthType;

    /** SSH 密码认证口令（仅 SSH_PASSWORD 生效）。 */
    private String sshPassword;

    /** SSH 私钥路径（仅 SSH_KEY_PATH 生效）。 */
    private String sshPrivateKeyPath;

    /** SSH 私钥文本（仅 SSH_KEY_TEXT 生效）。 */
    private String sshPrivateKeyText;

    /** SSH 私钥解密口令（私钥模式可选）。 */
    private String sshPrivateKeyPassphrase;
}
