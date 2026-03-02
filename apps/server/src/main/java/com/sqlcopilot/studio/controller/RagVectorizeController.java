package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.rag.RagDatabaseVectorizeStatusVO;
import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.rag.RagVectorizeEnqueueReq;
import com.sqlcopilot.studio.dto.rag.RagVectorizeEnqueueVO;
import com.sqlcopilot.studio.dto.rag.RagVectorizeStatusQueryReq;
import com.sqlcopilot.studio.service.RagVectorizeQueueService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag/vectorize")
@Validated
public class RagVectorizeController {

    private final RagVectorizeQueueService ragVectorizeQueueService;

    public RagVectorizeController(RagVectorizeQueueService ragVectorizeQueueService) {
        this.ragVectorizeQueueService = ragVectorizeQueueService;
    }

    @PostMapping("/enqueue")
    public ApiResponse<RagVectorizeEnqueueVO> enqueue(@Valid @RequestBody RagVectorizeEnqueueReq req) {
        // 关键操作：每个数据库仅允许存在一个排队/执行中的向量化任务。
        return ApiResponse.success(ragVectorizeQueueService.enqueue(req.getConnectionId(), req.getDatabaseName()));
    }

    @GetMapping("/status/list")
    public ApiResponse<List<RagDatabaseVectorizeStatusVO>> listStatus(@Valid RagVectorizeStatusQueryReq req) {
        return ApiResponse.success(ragVectorizeQueueService.listStatus(req.getConnectionId()));
    }
}
