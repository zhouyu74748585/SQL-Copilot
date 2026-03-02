package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.ai.AiGenerateSqlReq;
import com.sqlcopilot.studio.dto.ai.AiGenerateSqlVO;
import com.sqlcopilot.studio.dto.ai.AiRepairReq;
import com.sqlcopilot.studio.dto.ai.AiRepairVO;
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

    @PostMapping("/repair")
    public ApiResponse<AiRepairVO> repair(@Valid @RequestBody AiRepairReq req) {
        return ApiResponse.success(aiService.repairSql(req));
    }
}
