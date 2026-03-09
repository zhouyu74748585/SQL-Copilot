package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.schema.TableDataCommitReq;
import com.sqlcopilot.studio.dto.schema.TableDataCommitVO;
import com.sqlcopilot.studio.dto.schema.TableDataPageReq;
import com.sqlcopilot.studio.dto.schema.TableDataPageVO;

/**
 * 表数据浏览与编辑服务。
 */
public interface TableDataService {

    /**
     * 分页查询表数据。
     */
    TableDataPageVO page(TableDataPageReq req);

    /**
     * 提交表数据变更。
     */
    TableDataCommitVO commit(TableDataCommitReq req);
}
