package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.schema.TableCopyReq;
import com.sqlcopilot.studio.dto.schema.TableCopyTaskVO;
import com.sqlcopilot.studio.dto.schema.TableCopyVO;

public interface TableCopyService {

    TableCopyVO copyTable(TableCopyReq req);

    TableCopyTaskVO getTask(String taskId);
}
