package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.editor.ExportReq;
import com.sqlcopilot.studio.dto.editor.ExportResultVO;
import com.sqlcopilot.studio.dto.editor.DeleteHistorySessionReq;
import com.sqlcopilot.studio.dto.editor.ChartCacheReadVO;
import com.sqlcopilot.studio.dto.editor.ChartCacheSaveReq;
import com.sqlcopilot.studio.dto.editor.ChartCacheSaveVO;
import com.sqlcopilot.studio.dto.editor.QueryHistoryVO;
import com.sqlcopilot.studio.dto.editor.QueryHistorySessionPageVO;
import com.sqlcopilot.studio.dto.editor.SaveQueryHistoryReq;

import java.util.List;

public interface EditorService {

    List<QueryHistoryVO> listHistory(Long connectionId, Integer limit);

    QueryHistorySessionPageVO pageHistorySessions(Long connectionId, Integer pageNo, Integer pageSize, String keyword);

    List<QueryHistoryVO> listHistoryBySession(Long connectionId, String sessionId, Integer limit);

    void removeHistorySession(DeleteHistorySessionReq req);

    void saveHistory(SaveQueryHistoryReq req);

    ChartCacheSaveVO saveChartCache(ChartCacheSaveReq req);

    ChartCacheReadVO readChartCache(String cacheKey);

    ExportResultVO exportResult(ExportReq req);
}
