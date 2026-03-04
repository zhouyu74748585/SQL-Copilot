package com.sqlcopilot.studio.dto.connection;

import lombok.Data;
import java.util.List;

/** 连接数据库候选预览响应对象。 */
@Data
public class ConnectionDatabasePreviewVO {

    /** 候选数据库名称列表。 */
    private List<String> databaseNames;
}
