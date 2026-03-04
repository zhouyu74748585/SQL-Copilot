package com.sqlcopilot.studio.dto.connection;

import lombok.Data;

/** 连接数据库候选预览请求对象（用于未落库配置临时建连）。 */
@Data
public class ConnectionDatabasePreviewReq {

    /** 数据库类型：MYSQL/POSTGRESQL/SQLITE/SQLSERVER/ORACLE。 */
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

    /** 是否启用 SSH 隧道。 */
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
