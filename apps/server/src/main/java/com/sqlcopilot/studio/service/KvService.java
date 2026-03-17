package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.kv.KvObjectDetailVO;
import com.sqlcopilot.studio.dto.kv.KvOverviewVO;
import com.sqlcopilot.studio.dto.kv.KvQueryExecuteReq;
import com.sqlcopilot.studio.dto.schema.SchemaDatabaseVO;
import com.sqlcopilot.studio.dto.sql.SqlExecuteVO;

import java.util.List;

public interface KvService {

    List<SchemaDatabaseVO> listDatabases(Long connectionId);

    KvOverviewVO getOverview(Long connectionId, String databaseName);

    KvObjectDetailVO getObjectDetail(Long connectionId, String databaseName, String objectName);

    SqlExecuteVO executeQuery(KvQueryExecuteReq req);
}
