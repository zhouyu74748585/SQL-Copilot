package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.editor.DeleteHistorySessionReq;
import com.sqlcopilot.studio.dto.editor.ExportReq;
import com.sqlcopilot.studio.dto.editor.ExportResultVO;
import com.sqlcopilot.studio.dto.editor.QueryHistorySessionPageVO;
import com.sqlcopilot.studio.dto.editor.QueryHistoryVO;
import com.sqlcopilot.studio.dto.editor.SaveQueryHistoryReq;
import com.sqlcopilot.studio.service.EditorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/editor")
public class EditorController {

    private final EditorService editorService;

    public EditorController(EditorService editorService) {
        this.editorService = editorService;
    }

    @GetMapping("/history/list")
    public ApiResponse<List<QueryHistoryVO>> history(@RequestParam("connectionId") Long connectionId,
                                                     @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(editorService.listHistory(connectionId, limit));
    }

    @GetMapping("/history/session/page")
    public ApiResponse<QueryHistorySessionPageVO> historySessionPage(@RequestParam("connectionId") Long connectionId,
                                                                     @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                                     @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                                     @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.success(editorService.pageHistorySessions(connectionId, pageNo, pageSize, keyword));
    }

    @GetMapping("/history/session/detail")
    public ApiResponse<List<QueryHistoryVO>> historySessionDetail(@RequestParam("connectionId") Long connectionId,
                                                                  @RequestParam("sessionId") String sessionId,
                                                                  @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(editorService.listHistoryBySession(connectionId, sessionId, limit));
    }

    @PostMapping("/history/save")
    public ApiResponse<Boolean> saveHistory(@Valid @RequestBody SaveQueryHistoryReq req) {
        editorService.saveHistory(req);
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/history/session/remove")
    public ApiResponse<Boolean> removeHistorySession(@Valid @RequestBody DeleteHistorySessionReq req) {
        editorService.removeHistorySession(req);
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/result/export")
    public ApiResponse<ExportResultVO> export(@Valid @RequestBody ExportReq req) {
        return ApiResponse.success(editorService.exportResult(req));
    }
}
