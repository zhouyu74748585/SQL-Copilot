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

    /** 树中库/命名空间节点展示名称。 */
    private String namespaceLabel;

    /** 是否支持新建库/命名空间。 */
    private Boolean supportsNamespaceCreate;

    /** 是否支持重命名库/命名空间。 */
    private Boolean supportsNamespaceRename;

    /** 是否支持删除库/命名空间。 */
    private Boolean supportsNamespaceDrop;

    /** 是否支持新建表。 */
    private Boolean supportsTableCreate;

    /** 是否支持删除表。 */
    private Boolean supportsTableDrop;

    /** 是否支持新建视图。 */
    private Boolean supportsViewCreate;

    /** 是否支持删除视图。 */
    private Boolean supportsViewDrop;

    /** 是否支持新建函数。 */
    private Boolean supportsFunctionCreate;

    /** 是否支持删除函数。 */
    private Boolean supportsFunctionDrop;
}
