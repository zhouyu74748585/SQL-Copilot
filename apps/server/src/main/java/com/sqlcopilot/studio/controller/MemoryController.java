package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.memory.*;
import com.sqlcopilot.studio.service.MemoryService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/memory")
@Validated
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/entry/page")
    public ApiResponse<MemoryEntryPageVO> entryPage(@Valid MemoryEntryPageReq req) {
        return ApiResponse.success(memoryService.pageEntries(req));
    }

    @PostMapping("/entry/save")
    public ApiResponse<MemoryEntryVO> saveEntry(@Valid @RequestBody MemoryEntrySaveReq req) {
        return ApiResponse.success(memoryService.saveEntry(req));
    }

    @PostMapping("/entry/remove")
    public ApiResponse<Boolean> removeEntry(@Valid @RequestBody MemoryEntryRemoveReq req) {
        memoryService.removeEntry(req);
        return ApiResponse.success(Boolean.TRUE);
    }

    @GetMapping("/history/page")
    public ApiResponse<MemoryHistoryPageVO> historyPage(@Valid MemoryHistoryPageReq req) {
        return ApiResponse.success(memoryService.pageHistories(req));
    }

    @PostMapping("/history/remove")
    public ApiResponse<Boolean> removeHistory(@Valid @RequestBody MemoryHistoryRemoveReq req) {
        memoryService.removeHistory(req);
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/history/promote")
    public ApiResponse<MemoryEntryVO> promoteHistory(@Valid @RequestBody MemoryHistoryPromoteReq req) {
        return ApiResponse.success(memoryService.promoteHistory(req));
    }
}
