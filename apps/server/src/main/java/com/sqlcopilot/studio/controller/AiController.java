package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.ai.*;
import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.service.AiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/query")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/generate")
    public ApiResponse<AiGenerateSqlVO> generate(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：先构建 Schema 上下文再生成 SQL，避免无结构盲猜。
        return ApiResponse.success(aiService.generateSql(req));
    }

    @PostMapping("/auto")
    public ApiResponse<AiAutoQueryVO> auto(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：自动模式先识别用户意图，再按意图路由到不同能力。
        return ApiResponse.success(aiService.autoQuery(req));
    }

    @PostMapping("/generate-chart")
    public ApiResponse<AiGenerateChartVO> generateChart(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：图表生成返回 SQL + 结构化图表配置，前端可一键执行并渲染。
        return ApiResponse.success(aiService.generateChart(req));
    }

    @PostMapping("/explain")
    public ApiResponse<AiTextResponseVO> explain(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：解释 SQL 使用自然语言输出，不执行数据库 EXPLAIN。
        return ApiResponse.success(aiService.explainSql(req));
    }

    @PostMapping("/analyze")
    public ApiResponse<AiTextResponseVO> analyze(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：基于数据库元数据分析 SQL 合理性，输出结构化建议。
        return ApiResponse.success(aiService.analyzeSql(req));
    }

    @PostMapping("/repair")
    public ApiResponse<AiRepairVO> repair(@Valid @RequestBody AiRepairReq req) {
        return ApiResponse.success(aiService.repairSql(req));
    }
}
