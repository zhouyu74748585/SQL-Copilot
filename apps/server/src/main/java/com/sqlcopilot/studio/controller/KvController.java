package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.kv.KvObjectDetailVO;
import com.sqlcopilot.studio.dto.kv.KvOverviewVO;
import com.sqlcopilot.studio.dto.kv.KvQueryExecuteReq;
import com.sqlcopilot.studio.dto.schema.SchemaDatabaseVO;
import com.sqlcopilot.studio.dto.sql.SqlExecuteVO;
import com.sqlcopilot.studio.service.KvService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kv")
@Validated
public class KvController {

    private final KvService kvService;

    public KvController(KvService kvService) {
        this.kvService = kvService;
    }

    @GetMapping("/databases")
    public ApiResponse<List<SchemaDatabaseVO>> databases(@RequestParam("connectionId") Long connectionId) {
        return ApiResponse.success(kvService.listDatabases(connectionId));
    }

    @GetMapping("/overview")
    public ApiResponse<KvOverviewVO> overview(@RequestParam("connectionId") Long connectionId,
                                              @RequestParam(value = "databaseName", required = false) String databaseName) {
        return ApiResponse.success(kvService.getOverview(connectionId, databaseName));
    }

    @GetMapping("/object/detail")
    public ApiResponse<KvObjectDetailVO> objectDetail(@RequestParam("connectionId") Long connectionId,
                                                      @RequestParam(value = "databaseName", required = false) String databaseName,
                                                      @RequestParam("objectName") String objectName) {
        return ApiResponse.success(kvService.getObjectDetail(connectionId, databaseName, objectName));
    }

    @PostMapping("/query/execute")
    public ApiResponse<SqlExecuteVO> execute(@Valid @RequestBody KvQueryExecuteReq req) {
        return ApiResponse.success(kvService.executeQuery(req));
    }
}
