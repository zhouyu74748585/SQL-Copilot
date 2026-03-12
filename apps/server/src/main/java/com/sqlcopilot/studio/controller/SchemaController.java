package com.sqlcopilot.studio.controller;

import com.sqlcopilot.studio.dto.common.ApiResponse;
import com.sqlcopilot.studio.dto.rag.RagDatabaseVectorizeStatusVO;
import com.sqlcopilot.studio.dto.schema.*;
import com.sqlcopilot.studio.service.ErDiagramService;
import com.sqlcopilot.studio.service.RagVectorizeQueueService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.TableCopyService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/schema")
@Validated
public class SchemaController {

    private static final Logger log = LoggerFactory.getLogger(SchemaController.class);

    private final SchemaService schemaService;
    private final ErDiagramService erDiagramService;
    private final RagVectorizeQueueService ragVectorizeQueueService;
    private final TableCopyService tableCopyService;

    public SchemaController(SchemaService schemaService,
                            ErDiagramService erDiagramService,
                            RagVectorizeQueueService ragVectorizeQueueService,
                            TableCopyService tableCopyService) {
        this.schemaService = schemaService;
        this.erDiagramService = erDiagramService;
        this.ragVectorizeQueueService = ragVectorizeQueueService;
        this.tableCopyService = tableCopyService;
    }

    @PostMapping("/sync")
    public ApiResponse<SchemaSyncVO> sync(@Valid @RequestBody SchemaSyncReq req) {
        return ApiResponse.success(schemaService.syncSchema(req.getConnectionId(), req.getDatabaseName()));
    }

    @GetMapping("/overview")
    public ApiResponse<SchemaOverviewVO> overview(@RequestParam("connectionId") Long connectionId,
                                                  @RequestParam(value = "databaseName", required = false) String databaseName) {
        SchemaOverviewVO overview = schemaService.getOverview(connectionId, databaseName);
        tryEnqueueVectorizeTask(connectionId, overview.getDatabaseName());
        return ApiResponse.success(overview);
    }

    @GetMapping("/tableStats")
    public ApiResponse<SchemaTableStatsVO> tableStats(@RequestParam("connectionId") Long connectionId,
                                                      @RequestParam(value = "databaseName", required = false) String databaseName) {
        return ApiResponse.success(schemaService.getTableStats(connectionId, databaseName));
    }

    @GetMapping("/tableDetail")
    public ApiResponse<TableDetailVO> tableDetail(@RequestParam("connectionId") Long connectionId,
                                                   @RequestParam(value = "databaseName", required = false) String databaseName,
                                                   @RequestParam("tableName") String tableName) {
        return ApiResponse.success(schemaService.getTableDetail(connectionId, databaseName, tableName));
    }

    @GetMapping("/object/definition")
    public ApiResponse<SchemaObjectDefinitionVO> objectDefinition(@RequestParam("connectionId") Long connectionId,
                                                                  @RequestParam("databaseName") String databaseName,
                                                                  @RequestParam("objectType") String objectType,
                                                                  @RequestParam("objectName") String objectName) {
        return ApiResponse.success(schemaService.getObjectDefinition(connectionId, databaseName, objectType, objectName));
    }

    @GetMapping("/databases")
    public ApiResponse<List<SchemaDatabaseVO>> databases(@RequestParam("connectionId") Long connectionId) {
        List<String> databases = schemaService.listDatabases(connectionId);
        List<RagDatabaseVectorizeStatusVO> statuses = ragVectorizeQueueService.listStatus(connectionId);
        Map<String, RagDatabaseVectorizeStatusVO> statusMap = new LinkedHashMap<>();
        for (RagDatabaseVectorizeStatusVO item : statuses) {
            if (item.getDatabaseName() == null) {
                continue;
            }
            statusMap.put(normalizeDatabaseName(item.getDatabaseName()), item);
        }

        List<SchemaDatabaseVO> result = databases.stream().map(item -> {
            String databaseName = Objects.toString(item, "").trim();
            RagDatabaseVectorizeStatusVO status = statusMap.get(normalizeDatabaseName(databaseName));
            SchemaDatabaseVO vo = new SchemaDatabaseVO();
            vo.setDatabaseName(databaseName);
            vo.setVectorizeStatus(status == null ? "NOT_VECTORIZED" : status.getStatus());
            vo.setVectorizeMessage(status == null ? "暂无向量化数据" : status.getMessage());
            vo.setVectorizeUpdatedAt(status == null ? null : status.getUpdatedAt());
            return vo;
        }).toList();
        return ApiResponse.success(result);
    }

    @GetMapping("/objectNames")
    public ApiResponse<java.util.List<String>> objectNames(@RequestParam("connectionId") Long connectionId,
                                                           @RequestParam(value = "databaseName", required = false) String databaseName,
                                                           @RequestParam("objectType") String objectType) {
        return ApiResponse.success(schemaService.listObjectNames(connectionId, databaseName, objectType));
    }

    @PostMapping("/context/build")
    public ApiResponse<ContextBuildVO> buildContext(@Valid @RequestBody ContextBuildReq req) {
        return ApiResponse.success(schemaService.buildContext(req));
    }

    @PostMapping("/er/graph")
    public ApiResponse<ErGraphVO> erGraph(@Valid @RequestBody ErGraphReq req) {
        return ApiResponse.success(erDiagramService.buildErGraph(req));
    }

    @PostMapping("/table/create")
    public ApiResponse<TableOperationVO> createTable(@Valid @RequestBody TableCreateReq req) {
        return ApiResponse.success(schemaService.createTable(req));
    }

    @PostMapping("/table/alter")
    public ApiResponse<TableOperationVO> alterTable(@Valid @RequestBody TableAlterReq req) {
        return ApiResponse.success(schemaService.alterTable(req));
    }

    @PostMapping("/namespace/create")
    public ApiResponse<SchemaNamespaceOperationVO> createNamespace(@Valid @RequestBody SchemaNamespaceCreateReq req) {
        return ApiResponse.success(schemaService.createNamespace(req));
    }

    @PostMapping("/namespace/rename")
    public ApiResponse<SchemaNamespaceOperationVO> renameNamespace(@Valid @RequestBody SchemaNamespaceRenameReq req) {
        return ApiResponse.success(schemaService.renameNamespace(req));
    }

    @PostMapping("/namespace/drop")
    public ApiResponse<SchemaNamespaceOperationVO> dropNamespace(@Valid @RequestBody SchemaNamespaceDropReq req) {
        return ApiResponse.success(schemaService.dropNamespace(req));
    }

    @PostMapping("/table/rename")
    public ApiResponse<TableRenameVO> renameTable(@Valid @RequestBody TableRenameReq req) {
        TableRenameVO result = schemaService.renameTable(req);
        if (result.isSuccess()) {
            schemaService.refreshSchemaCache(req.getConnectionId(), req.getDatabaseName());
            ragVectorizeQueueService.enqueue(req.getConnectionId(), req.getDatabaseName());
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/object/definition/save")
    public ApiResponse<SchemaObjectDefinitionSaveVO> saveObjectDefinition(@Valid @RequestBody SchemaObjectDefinitionSaveReq req) {
        SchemaObjectDefinitionSaveVO result = schemaService.saveObjectDefinition(req);
        if (result.isSuccess()) {
            schemaService.refreshSchemaCache(req.getConnectionId(), req.getDatabaseName());
            ragVectorizeQueueService.enqueue(req.getConnectionId(), req.getDatabaseName());
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/object/drop")
    public ApiResponse<SchemaObjectDropVO> dropObject(@Valid @RequestBody SchemaObjectDropReq req) {
        SchemaObjectDropVO result = schemaService.dropObject(req);
        if (result.isSuccess()) {
            schemaService.refreshSchemaCache(req.getConnectionId(), req.getDatabaseName());
            ragVectorizeQueueService.enqueue(req.getConnectionId(), req.getDatabaseName());
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/table/copy")
    public ApiResponse<TableCopyVO> copyTable(@Valid @RequestBody TableCopyReq req) {
        return ApiResponse.success(tableCopyService.copyTable(req));
    }

    @GetMapping("/table/copy/task")
    public ApiResponse<TableCopyTaskVO> copyTask(@RequestParam("taskId") String taskId) {
        return ApiResponse.success(tableCopyService.getTask(taskId));
    }

    @PostMapping("/table/drop")
    public ApiResponse<TableOperationVO> dropTable(@Valid @RequestBody TableDropReq req) {
        TableOperationVO result = schemaService.dropTable(req.getConnectionId(), req.getDatabaseName(), req.getTableName());
        if (result.isSuccess()) {
            schemaService.refreshSchemaCache(req.getConnectionId(), req.getDatabaseName());
            ragVectorizeQueueService.enqueue(req.getConnectionId(), req.getDatabaseName());
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/table/truncate")
    public ApiResponse<TableOperationVO> truncateTable(@Valid @RequestBody TableTruncateReq req) {
        TableOperationVO result = schemaService.truncateTable(req.getConnectionId(), req.getDatabaseName(), req.getTableName());
        if (result.isSuccess()) {
            schemaService.refreshSchemaCache(req.getConnectionId(), req.getDatabaseName());
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/cache/refresh")
    public ApiResponse<Void> refreshCache(@Valid @RequestBody SchemaCacheRefreshReq req) {
        schemaService.refreshSchemaCache(req.getConnectionId(), req.getDatabaseName());
        return ApiResponse.success(null);
    }

    private void tryEnqueueVectorizeTask(Long connectionId, String databaseName) {
        if (databaseName == null || databaseName.isBlank()) {
            return;
        }
        try {
            // 关键操作：Schema 查询链路仅做状态判断与入队，避免同步执行向量化阻塞请求。
            RagDatabaseVectorizeStatusVO status = ragVectorizeQueueService.getStatus(connectionId, databaseName);
            if (!"NOT_VECTORIZED".equals(status.getStatus())) {
                return;
            }
            ragVectorizeQueueService.enqueue(connectionId, databaseName);
        } catch (Exception ex) {
            log.warn("自动追加向量化任务失败, connectionId={}, databaseName={}, reason={}",
                connectionId, databaseName, ex.getMessage());
        }
    }

    private String normalizeDatabaseName(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }
}
