package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.rag.RagConfigSaveReq;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag/config")
public class RagConfigController {

    private final RagConfigService ragConfigService;

    public RagConfigController(RagConfigService ragConfigService) {
        this.ragConfigService = ragConfigService;
    }

    @GetMapping("/get")
    public ApiResponse<RagConfigVO> get() {
        return ApiResponse.success(ragConfigService.getConfig());
    }

    @PostMapping("/save")
    public ApiResponse<RagConfigVO> save(@Valid @RequestBody RagConfigSaveReq req) {
        // 关键操作：RAG 模型配置独立保存，避免与 LLM 接入配置耦合。
        return ApiResponse.success(ragConfigService.saveConfig(req));
    }
}
