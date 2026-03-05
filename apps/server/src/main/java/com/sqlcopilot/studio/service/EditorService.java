package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.editor.*;

import java.util.List;

public interface EditorService {

    List<QueryHistoryVO> listHistory(Long connectionId, Integer limit);

    QueryHistorySessionPageVO pageHistorySessions(Long connectionId, Integer pageNo, Integer pageSize, String keyword);

    List<QueryHistoryVO> listHistoryBySession(Long connectionId, String sessionId, Integer limit);

    ErGraphSnapshotPageVO pageErGraphSnapshots(Long connectionId, Integer pageNo, Integer pageSize, String keyword);

    ErGraphSnapshotVO getErGraphSnapshotDetail(Long snapshotId);

    void renameErGraphSnapshot(RenameErGraphSnapshotReq req);

    void removeErGraphSnapshot(DeleteErGraphSnapshotReq req);

    void removeHistorySession(DeleteHistorySessionReq req);

    void saveHistory(SaveQueryHistoryReq req);

    void saveErGraphSnapshot(ErGraphSnapshotSaveReq req);

    ChartCacheSaveVO saveChartCache(ChartCacheSaveReq req);

    ChartCacheReadVO readChartCache(String cacheKey);

    ExportResultVO exportResult(ExportReq req);
}
