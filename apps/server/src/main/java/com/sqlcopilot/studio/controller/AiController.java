package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.ai.*;
import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.service.AiService;
import com.sqlcopilot.studio.service.stream.AiStreamObserver;
import com.sqlcopilot.studio.service.stream.SseAiStreamObserver;
import com.sqlcopilot.studio.util.BusinessException;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

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

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@Valid @RequestBody AiGenerateSqlReq req) {
        return streamGenerate(req, "generate", observer -> aiService.generateSql(req, observer));
    }

    @PostMapping("/auto")
    public ApiResponse<AiAutoQueryVO> auto(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：自动模式先识别用户意图，再按意图路由到不同能力。
        return ApiResponse.success(aiService.autoQuery(req));
    }

    @PostMapping(value = "/auto/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter autoStream(@Valid @RequestBody AiGenerateSqlReq req) {
        return streamGenerate(req, "auto", observer -> aiService.autoQuery(req, observer));
    }

    @PostMapping("/generate-chart")
    public ApiResponse<AiGenerateChartVO> generateChart(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：图表生成返回 SQL + 结构化图表配置，前端可一键执行并渲染。
        return ApiResponse.success(aiService.generateChart(req));
    }

    @PostMapping(value = "/generate-chart/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateChartStream(@Valid @RequestBody AiGenerateSqlReq req) {
        return streamGenerate(req, "generate-chart", observer -> aiService.generateChart(req, observer));
    }

    @PostMapping("/explain")
    public ApiResponse<AiTextResponseVO> explain(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：解释 SQL 使用自然语言输出，不执行数据库 EXPLAIN。
        return ApiResponse.success(aiService.explainSql(req));
    }

    @PostMapping(value = "/explain/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter explainStream(@Valid @RequestBody AiGenerateSqlReq req) {
        return streamGenerate(req, "explain", observer -> aiService.explainSql(req, observer));
    }

    @PostMapping("/analyze")
    public ApiResponse<AiTextResponseVO> analyze(@Valid @RequestBody AiGenerateSqlReq req) {
        // 关键操作：基于数据库元数据分析 SQL 合理性，输出结构化建议。
        return ApiResponse.success(aiService.analyzeSql(req));
    }

    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@Valid @RequestBody AiGenerateSqlReq req) {
        return streamGenerate(req, "analyze", observer -> aiService.analyzeSql(req, observer));
    }

    @PostMapping("/repair")
    public ApiResponse<AiRepairVO> repair(@Valid @RequestBody AiRepairReq req) {
        return ApiResponse.success(aiService.repairSql(req));
    }

    @PostMapping(value = "/repair/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter repairStream(@Valid @RequestBody AiRepairReq req) {
        return streamGenerate(req, "repair", observer -> aiService.repairSql(req, observer));
    }

    private <T> SseEmitter streamGenerate(AiGenerateSqlReq req, String actionType, StreamSupplier<T> supplier) {
        return streamInternal(req.getSessionId(), actionType, supplier);
    }

    private <T> SseEmitter streamGenerate(AiRepairReq req, String actionType, StreamSupplier<T> supplier) {
        return streamInternal(req.getSessionId(), actionType, supplier);
    }

    private <T> SseEmitter streamInternal(String sessionId, String actionType, StreamSupplier<T> supplier) {
        SseEmitter emitter = new SseEmitter(0L);
        AiStreamObserver observer = new SseAiStreamObserver(emitter);
        CompletableFuture.runAsync(() -> {
            try {
                observer.onSessionStarted(sessionId, actionType);
                supplier.get(observer);
                observer.onDone(sessionId, actionType);
                emitter.complete();
            } catch (BusinessException ex) {
                observer.onError(sessionId, actionType, ex.getCode(), ex.getMessage());
                emitter.complete();
            } catch (Exception ex) {
                observer.onError(sessionId, actionType, 500, ex.getMessage());
                emitter.complete();
            }
        });
        return emitter;
    }

    @FunctionalInterface
    private interface StreamSupplier<T> {
        T get(AiStreamObserver observer);
    }
}
