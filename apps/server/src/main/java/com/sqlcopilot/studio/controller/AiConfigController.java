package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.ai.AiConfigSaveReq;
import com.sqlcopilot.studio.dto.ai.AiConfigVO;
import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.service.AiConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/config")
public class AiConfigController {

    private final AiConfigService aiConfigService;

    public AiConfigController(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    @GetMapping("/get")
    public ApiResponse<AiConfigVO> get() {
        return ApiResponse.success(aiConfigService.getConfig());
    }

    @PostMapping("/save")
    public ApiResponse<AiConfigVO> save(@Valid @RequestBody AiConfigSaveReq req) {
        // 关键操作：配置变更统一经后端持久化，确保 AI 路由行为可复现。
        return ApiResponse.success(aiConfigService.saveConfig(req));
    }
}
