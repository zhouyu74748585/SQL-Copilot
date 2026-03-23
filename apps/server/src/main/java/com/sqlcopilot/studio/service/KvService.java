package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.kv.KvObjectDetailVO;
import com.sqlcopilot.studio.dto.kv.KvOverviewVO;
import com.sqlcopilot.studio.dto.kv.KvQueryExecuteReq;
import com.sqlcopilot.studio.dto.kv.KvRedisBrowserPageVO;
import com.sqlcopilot.studio.dto.kv.KvRedisKeyDeleteReq;
import com.sqlcopilot.studio.dto.kv.KvRedisKeyDeleteVO;
import com.sqlcopilot.studio.dto.kv.KvRedisKeySaveReq;
import com.sqlcopilot.studio.dto.kv.KvRedisKeySaveVO;
import com.sqlcopilot.studio.dto.schema.SchemaDatabaseVO;
import com.sqlcopilot.studio.dto.sql.SqlExecuteVO;

import java.util.List;

public interface KvService {

    List<SchemaDatabaseVO> listDatabases(Long connectionId);

    KvOverviewVO getOverview(Long connectionId, String databaseName);

    KvRedisBrowserPageVO browseRedis(Long connectionId,
                                     String databaseName,
                                     String parentPath,
                                     String keyword,
                                     String cursor,
                                     Integer pageSize);

    KvObjectDetailVO getObjectDetail(Long connectionId, String databaseName, String objectName);

    KvRedisBrowserPageVO browseRedis(Long connectionId,
                                     String databaseName,
                                     String parentPath,
                                     String keyword,
                                     String cursor,
                                     Integer pageSize);

    SqlExecuteVO executeQuery(KvQueryExecuteReq req);

    KvRedisKeySaveVO createRedisKey(KvRedisKeySaveReq req);

    KvRedisKeySaveVO updateRedisKey(KvRedisKeySaveReq req);

    KvRedisKeyDeleteVO deleteRedisKey(KvRedisKeyDeleteReq req);
}
