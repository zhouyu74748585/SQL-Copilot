package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.schema.TableDataCommitReq;
import com.sqlcopilot.studio.dto.schema.TableDataCommitVO;
import com.sqlcopilot.studio.dto.schema.TableDataPageReq;
import com.sqlcopilot.studio.dto.schema.TableDataPageVO;
import com.sqlcopilot.studio.service.TableDataService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表数据浏览与编辑接口。
 */
@RestController
@RequestMapping("/api/schema/table/data")
public class TableDataController {

    private final TableDataService tableDataService;

    public TableDataController(TableDataService tableDataService) {
        this.tableDataService = tableDataService;
    }

    @PostMapping("/page")
    public ApiResponse<TableDataPageVO> page(@Valid @RequestBody TableDataPageReq req) {
        return ApiResponse.success(tableDataService.page(req));
    }

    @PostMapping("/commit")
    public ApiResponse<TableDataCommitVO> commit(@Valid @RequestBody TableDataCommitReq req) {
        // 关键操作：提交采用单事务全成全败，避免产生部分成功数据。
        return ApiResponse.success(tableDataService.commit(req));
    }
}
