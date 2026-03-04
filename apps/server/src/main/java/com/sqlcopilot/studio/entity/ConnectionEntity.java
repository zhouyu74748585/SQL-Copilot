package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class ConnectionEntity {
    private Long id;
    private String name;
    private String dbType;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String password;
    private String authType;
    private String env;
    private Integer readOnly;
    private Integer sshEnabled;
    private String sshHost;
    private Integer sshPort;
    private String sshUser;
    private String sshAuthType;
    private String sshPassword;
    private String sshPrivateKeyPath;
    private String sshPrivateKeyText;
    private String sshPrivateKeyPassphrase;
    private String selectedDatabasesJson;
    private String lastTestStatus;
    private String lastTestMessage;
    private Long createdAt;
    private Long updatedAt;
}
