package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.sql.*;
import com.sqlcopilot.studio.service.SqlService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sql")
public class SqlController {

    private final SqlService sqlService;

    public SqlController(SqlService sqlService) {
        this.sqlService = sqlService;
    }

    @PostMapping("/explain")
    public ApiResponse<ExplainVO> explain(@Valid @RequestBody ExplainReq req) {
        return ApiResponse.success(sqlService.explain(req));
    }

    @PostMapping("/risk/evaluate")
    public ApiResponse<RiskEvaluateVO> evaluate(@Valid @RequestBody RiskEvaluateReq req) {
        return ApiResponse.success(sqlService.evaluateRisk(req));
    }

    @PostMapping("/execute")
    public ApiResponse<SqlExecuteVO> execute(@Valid @RequestBody SqlExecuteReq req) {
        // 关键操作：执行前统一进行风险校验与只读策略拦截。
        return ApiResponse.success(sqlService.execute(req));
    }
}
