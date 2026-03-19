package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

import java.util.List;

/** Redis 树表格分页结果对象。 */
@Data
public class KvRedisBrowserPageVO {

    /** 连接 ID。 */
    private Long connectionId;

    /** 逻辑库名称。 */
    private String databaseName;

    /** 当前查询根路径。 */
    private String parentPath;

    /** 当前搜索关键词。 */
    private String keyword;

    /** 当前请求游标。 */
    private String cursor;

    /** 下一页游标。 */
    private String nextCursor;

    /** 是否已扫描完成。 */
    private Boolean finished;

    /** 当前页节点列表。 */
    private List<KvRedisBrowserNodeVO> items;
}
