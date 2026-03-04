package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.connection.*;
import com.sqlcopilot.studio.service.ConnectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/connection")
public class ConnectionController {

    private final ConnectionService connectionService;

    public ConnectionController(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @GetMapping("/list")
    public ApiResponse<List<ConnectionVO>> list() {
        return ApiResponse.success(connectionService.listConnections());
    }

    @PostMapping("/create")
    public ApiResponse<ConnectionVO> create(@Valid @RequestBody ConnectionCreateReq req) {
        // 关键操作：新建连接时统一落库并附加环境策略。
        return ApiResponse.success(connectionService.createConnection(req));
    }

    @PostMapping("/update")
    public ApiResponse<ConnectionVO> update(@Valid @RequestBody ConnectionUpdateReq req) {
        return ApiResponse.success(connectionService.updateConnection(req));
    }

    @PostMapping("/databases/preview")
    public ApiResponse<ConnectionDatabasePreviewVO> previewDatabases(@RequestBody ConnectionDatabasePreviewReq req) {
        // 关键操作：弹窗未保存配置下临时建连预览库列表，不写入 connection_info。
        return ApiResponse.success(connectionService.previewDatabases(req));
    }

    @PostMapping("/remove")
    public ApiResponse<Boolean> remove(@Valid @RequestBody ConnectionRemoveReq req) {
        connectionService.removeConnection(req.getId());
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/test")
    public ApiResponse<ConnectionTestVO> test(@Valid @RequestBody ConnectionTestReq req) {
        return ApiResponse.success(connectionService.testConnection(req.getConnectionId()));
    }
}
