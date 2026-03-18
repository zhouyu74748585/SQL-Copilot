package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.connection.*;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.MetadataChangeSyncService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/connection")
public class ConnectionController {

    private final ConnectionService connectionService;
    private final MetadataChangeSyncService metadataChangeSyncService;

    public ConnectionController(ConnectionService connectionService,
                                MetadataChangeSyncService metadataChangeSyncService) {
        this.connectionService = connectionService;
        this.metadataChangeSyncService = metadataChangeSyncService;
    }

    @GetMapping("/db-types")
    public ApiResponse<List<ConnectionDbTypeVO>> listSupportedDbTypes() {
        return ApiResponse.success(connectionService.listSupportedDbTypes());
    }

    @GetMapping("/group/list")
    public ApiResponse<List<ConnectionGroupVO>> listGroups() {
        return ApiResponse.success(connectionService.listConnectionGroups());
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

    @PostMapping("/group/create")
    public ApiResponse<ConnectionGroupVO> createGroup(@Valid @RequestBody ConnectionGroupCreateReq req) {
        return ApiResponse.success(connectionService.createConnectionGroup(req));
    }

    @PostMapping("/group/rename")
    public ApiResponse<ConnectionGroupVO> renameGroup(@Valid @RequestBody ConnectionGroupRenameReq req) {
        return ApiResponse.success(connectionService.renameConnectionGroup(req));
    }

    @PostMapping("/group/remove")
    public ApiResponse<Boolean> removeGroup(@Valid @RequestBody ConnectionGroupRemoveReq req) {
        connectionService.removeConnectionGroup(req.getGroupId());
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/group/move")
    public ApiResponse<Boolean> moveToGroup(@Valid @RequestBody ConnectionGroupMoveReq req) {
        connectionService.moveConnectionToGroup(req.getConnectionId(), req.getTargetGroupId());
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/remove")
    public ApiResponse<Boolean> remove(@Valid @RequestBody ConnectionRemoveReq req) {
        connectionService.removeConnection(req.getId());
        metadataChangeSyncService.onConnectionRemoved(req.getId());
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/disconnect")
    public ApiResponse<Boolean> disconnect(@Valid @RequestBody ConnectionRemoveReq req) {
        connectionService.disconnectConnection(req.getId());
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/test")
    public ApiResponse<ConnectionTestVO> test(@Valid @RequestBody ConnectionTestReq req) {
        return ApiResponse.success(connectionService.testConnection(req.getConnectionId()));
    }
}
