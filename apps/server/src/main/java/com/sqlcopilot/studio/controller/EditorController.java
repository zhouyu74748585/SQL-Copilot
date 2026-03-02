package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.editor.ExportReq;
import com.sqlcopilot.studio.dto.editor.ExportResultVO;
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

    @PostMapping("/history/save")
    public ApiResponse<Boolean> saveHistory(@Valid @RequestBody SaveQueryHistoryReq req) {
        editorService.saveHistory(req);
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/result/export")
    public ApiResponse<ExportResultVO> export(@Valid @RequestBody ExportReq req) {
        return ApiResponse.success(editorService.exportResult(req));
    }
}
