package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.rag.*;
import com.sqlcopilot.studio.service.RagVectorizeQueueService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag/vectorize")
@Validated
public class RagVectorizeController {

    private final RagVectorizeQueueService ragVectorizeQueueService;
    private final RagEmbeddingService ragEmbeddingService;

    public RagVectorizeController(RagVectorizeQueueService ragVectorizeQueueService,
                                  RagEmbeddingService ragEmbeddingService) {
        this.ragVectorizeQueueService = ragVectorizeQueueService;
        this.ragEmbeddingService = ragEmbeddingService;
    }

    @PostMapping("/enqueue")
    public ApiResponse<RagVectorizeEnqueueVO> enqueue(@Valid @RequestBody RagVectorizeEnqueueReq req) {
        // 关键操作：每个数据库仅允许存在一个排队/执行中的向量化任务。
        return ApiResponse.success(ragVectorizeQueueService.enqueue(req.getConnectionId(), req.getDatabaseName()));
    }

    @PostMapping("/interrupt")
    public ApiResponse<RagVectorizeInterruptVO> interrupt(@Valid @RequestBody RagVectorizeInterruptReq req) {
        // 关键操作：中断仅改变当前库任务状态，不影响其他数据库队列任务。
        return ApiResponse.success(ragVectorizeQueueService.interrupt(req.getConnectionId(), req.getDatabaseName()));
    }

    @PostMapping("/table/manual")
    public ApiResponse<RagVectorizeTableVO> vectorizeTable(@Valid @RequestBody RagVectorizeTableReq req) {
        // 关键操作：单表向量化直接写入 schema_table/schema_column 集合，不进入数据库全量队列。
        return ApiResponse.success(ragVectorizeQueueService.vectorizeTable(
            req.getConnectionId(),
            req.getDatabaseName(),
            req.getTableName()
        ));
    }

    @GetMapping("/status/list")
    public ApiResponse<List<RagDatabaseVectorizeStatusVO>> listStatus(@Valid RagVectorizeStatusQueryReq req) {
        return ApiResponse.success(ragVectorizeQueueService.listStatus(req.getConnectionId()));
    }

    @GetMapping("/overview")
    public ApiResponse<RagVectorizeOverviewVO> overview(@Valid RagVectorizeOverviewQueryReq req) {
        return ApiResponse.success(ragVectorizeQueueService.getOverview(req.getConnectionId(), req.getDatabaseName()));
    }

    @GetMapping("/runtime-provider")
    public ApiResponse<Map<String, String>> runtimeProvider() {
        return ApiResponse.success(Map.of("provider", ragEmbeddingService.getRuntimeProvider()));
    }
}
