package com.sqlcopilot.studio.service.rag;

import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.entity.SchemaColumnCacheEntity;
import com.sqlcopilot.studio.entity.SchemaTableCacheEntity;

import java.util.List;

public interface RagIngestionService {

    void ingestSchema(Long connectionId,
                      String databaseName,
                      List<SchemaTableCacheEntity> tableMetaList,
                      List<SchemaColumnCacheEntity> columnMetaList);

    void ingestSqlHistory(QueryHistoryEntity historyEntity);
}
