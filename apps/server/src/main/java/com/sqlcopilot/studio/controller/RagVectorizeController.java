package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.rag.RagDatabaseVectorizeStatusVO;
import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.rag.RagVectorizeEnqueueReq;
import com.sqlcopilot.studio.dto.rag.RagVectorizeEnqueueVO;
import com.sqlcopilot.studio.dto.rag.RagVectorizeInterruptReq;
import com.sqlcopilot.studio.dto.rag.RagVectorizeInterruptVO;
import com.sqlcopilot.studio.dto.rag.RagVectorizeOverviewQueryReq;
import com.sqlcopilot.studio.dto.rag.RagVectorizeOverviewVO;
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

    @PostMapping("/interrupt")
    public ApiResponse<RagVectorizeInterruptVO> interrupt(@Valid @RequestBody RagVectorizeInterruptReq req) {
        // 关键操作：中断仅改变当前库任务状态，不影响其他数据库队列任务。
        return ApiResponse.success(ragVectorizeQueueService.interrupt(req.getConnectionId(), req.getDatabaseName()));
    }

    @GetMapping("/status/list")
    public ApiResponse<List<RagDatabaseVectorizeStatusVO>> listStatus(@Valid RagVectorizeStatusQueryReq req) {
        return ApiResponse.success(ragVectorizeQueueService.listStatus(req.getConnectionId()));
    }

    @GetMapping("/overview")
    public ApiResponse<RagVectorizeOverviewVO> overview(@Valid RagVectorizeOverviewQueryReq req) {
        return ApiResponse.success(ragVectorizeQueueService.getOverview(req.getConnectionId(), req.getDatabaseName()));
    }
}
