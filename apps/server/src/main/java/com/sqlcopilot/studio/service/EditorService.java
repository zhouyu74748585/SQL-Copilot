package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.editor.ExportReq;
import com.sqlcopilot.studio.dto.editor.ExportResultVO;
import com.sqlcopilot.studio.dto.editor.QueryHistoryVO;
import com.sqlcopilot.studio.dto.editor.SaveQueryHistoryReq;

import java.util.List;

public interface EditorService {

    List<QueryHistoryVO> listHistory(Long connectionId, Integer limit);

    void saveHistory(SaveQueryHistoryReq req);

    ExportResultVO exportResult(ExportReq req);
}
