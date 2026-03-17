package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

import java.util.List;

/** KV/文档对象浏览概览。 */
@Data
public class KvOverviewVO {

    /** 连接 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 主对象展示标签。 */
    private String objectLabel;

    /** 对象数量。 */
    private Integer objectCount;

    /** 对象摘要列表。 */
    private List<KvObjectSummaryVO> objects;
}
