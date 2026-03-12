package com.sqlcopilot.studio.dto.connection;

import lombok.Data;

/** 数据库类型选项响应对象。 */
@Data
public class ConnectionDbTypeVO {

    /** 数据库类型编码。 */
    private String dbType;

    /** 数据库类型展示名称。 */
    private String displayName;

    /** 默认连接端口。 */
    private Integer defaultPort;

    /** 是否支持预览并勾选数据库或 Schema。 */
    private Boolean supportsSelectedDatabases;
}
