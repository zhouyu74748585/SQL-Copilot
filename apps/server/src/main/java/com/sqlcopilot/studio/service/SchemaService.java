package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.schema.*;

import java.util.List;

public interface SchemaService {

    SchemaSyncVO syncSchema(Long connectionId, String databaseName);

    SchemaOverviewVO getOverview(Long connectionId, String databaseName);

    SchemaTableStatsVO getTableStats(Long connectionId, String databaseName);

    TableDetailVO getTableDetail(Long connectionId, String databaseName, String tableName);

    List<String> listDatabases(Long connectionId);

    List<String> listObjectNames(Long connectionId, String databaseName, String objectType);

    ContextBuildVO buildContext(ContextBuildReq req);

    TableOperationVO createTable(TableCreateReq req);

    TableOperationVO alterTable(TableAlterReq req);
}
