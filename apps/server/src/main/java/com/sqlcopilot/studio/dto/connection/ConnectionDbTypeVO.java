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

    /** 存储类型。 */
    private String storageKind;

    /** 主对象分组展示名称。 */
    private String primaryObjectLabel;

    /** 查询编辑模式。 */
    private String queryEditorMode;

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

    /** 是否支持 AI 生成查询。 */
    private Boolean supportsGenerateQuery;

    /** 是否支持 AI 解释查询。 */
    private Boolean supportsExplainQuery;

    /** 是否支持 AI 分析查询。 */
    private Boolean supportsAnalyzeQuery;

    /** 是否支持 AI 生成图表。 */
    private Boolean supportsGenerateChart;

    /** 是否显示主机输入。 */
    private Boolean requiresHost;

    /** 是否显示端口输入。 */
    private Boolean requiresPort;

    /** 是否显示数据库名输入。 */
    private Boolean supportsDatabaseName;

    /** 是否支持预览数据库或 Schema。 */
    private Boolean supportsDatabasePreview;

    /** 数据库名字段标签。 */
    private String databaseNameLabel;

    /** 是否显示用户名输入。 */
    private Boolean supportsUsername;

    /** 是否显示密码输入。 */
    private Boolean supportsPassword;
}
