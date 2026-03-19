package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

import java.util.List;

/** Redis 浏览分页结果。 */
@Data
public class KvRedisBrowserPageVO {

    private Long connectionId;

    private String databaseName;

    private String parentPath;

    private String keyword;

    private String cursor;

    private String nextCursor;

    private Boolean finished;

    private List<KvRedisBrowserNodeVO> items;
}
