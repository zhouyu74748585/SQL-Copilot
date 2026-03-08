package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.knowledge.*;
import com.sqlcopilot.studio.service.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@Validated
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/term/list")
    public ApiResponse<List<KnowledgeTermVO>> termList(@Valid KnowledgeListQueryReq req) {
        return ApiResponse.success(knowledgeService.listTerms(req));
    }

    @PostMapping("/term/save")
    public ApiResponse<KnowledgeTermVO> saveTerm(@Valid @RequestBody KnowledgeTermSaveReq req) {
        return ApiResponse.success(knowledgeService.saveTerm(req));
    }

    @PostMapping("/term/remove")
    public ApiResponse<Boolean> removeTerm(@Valid @RequestBody KnowledgeTermRemoveReq req) {
        knowledgeService.removeTerm(req);
        return ApiResponse.success(Boolean.TRUE);
    }

    @GetMapping("/example/list")
    public ApiResponse<List<KnowledgeExampleSqlVO>> exampleList(@Valid KnowledgeListQueryReq req) {
        return ApiResponse.success(knowledgeService.listExamples(req));
    }

    @PostMapping("/example/save")
    public ApiResponse<KnowledgeExampleSqlVO> saveExample(@Valid @RequestBody KnowledgeExampleSqlSaveReq req) {
        return ApiResponse.success(knowledgeService.saveExample(req));
    }

    @PostMapping("/example/remove")
    public ApiResponse<Boolean> removeExample(@Valid @RequestBody KnowledgeExampleSqlRemoveReq req) {
        knowledgeService.removeExample(req);
        return ApiResponse.success(Boolean.TRUE);
    }

    @PostMapping("/vectorize/rebuild")
    public ApiResponse<KnowledgeVectorRebuildVO> rebuildVectors(@Valid @RequestBody KnowledgeVectorRebuildReq req) {
        return ApiResponse.success(knowledgeService.rebuildVectors(req));
    }
}
