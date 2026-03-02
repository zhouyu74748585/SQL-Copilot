package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.schema.*;
import com.sqlcopilot.studio.service.SchemaService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/schema")
@Validated
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @PostMapping("/sync")
    public ApiResponse<SchemaSyncVO> sync(@Valid @RequestBody SchemaSyncReq req) {
        return ApiResponse.success(schemaService.syncSchema(req.getConnectionId(), req.getDatabaseName()));
    }

    @GetMapping("/overview")
    public ApiResponse<SchemaOverviewVO> overview(@RequestParam("connectionId") Long connectionId,
                                                  @RequestParam(value = "databaseName", required = false) String databaseName) {
        return ApiResponse.success(schemaService.getOverview(connectionId, databaseName));
    }

    @GetMapping("/tableDetail")
    public ApiResponse<TableDetailVO> tableDetail(@RequestParam("connectionId") Long connectionId,
                                                   @RequestParam(value = "databaseName", required = false) String databaseName,
                                                   @RequestParam("tableName") String tableName) {
        return ApiResponse.success(schemaService.getTableDetail(connectionId, databaseName, tableName));
    }

    @GetMapping("/databases")
    public ApiResponse<java.util.List<String>> databases(@RequestParam("connectionId") Long connectionId) {
        return ApiResponse.success(schemaService.listDatabases(connectionId));
    }

    @GetMapping("/objectNames")
    public ApiResponse<java.util.List<String>> objectNames(@RequestParam("connectionId") Long connectionId,
                                                           @RequestParam(value = "databaseName", required = false) String databaseName,
                                                           @RequestParam("objectType") String objectType) {
        return ApiResponse.success(schemaService.listObjectNames(connectionId, databaseName, objectType));
    }

    @PostMapping("/context/build")
    public ApiResponse<ContextBuildVO> buildContext(@Valid @RequestBody ContextBuildReq req) {
        return ApiResponse.success(schemaService.buildContext(req));
    }
}
