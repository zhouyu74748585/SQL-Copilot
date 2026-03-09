package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/**
 * 表数据提交响应。
 */
@Data
public class TableDataCommitVO {

    /** 是否成功。 */
    private boolean success;

    /** 返回消息。 */
    private String message;

    /** 新增条数。 */
    private Integer insertedCount;

    /** 更新条数。 */
    private Integer updatedCount;

    /** 删除条数。 */
    private Integer deletedCount;

    /**
     * 构建成功响应。
     */
    public static TableDataCommitVO success(String message, int insertedCount, int updatedCount, int deletedCount) {
        TableDataCommitVO vo = new TableDataCommitVO();
        vo.setSuccess(true);
        vo.setMessage(message);
        vo.setInsertedCount(insertedCount);
        vo.setUpdatedCount(updatedCount);
        vo.setDeletedCount(deletedCount);
        return vo;
    }
}
