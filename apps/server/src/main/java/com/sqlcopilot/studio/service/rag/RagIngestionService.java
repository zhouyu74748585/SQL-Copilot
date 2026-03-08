package com.sqlcopilot.studio.service.rag;

import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.entity.KnowledgeExampleSqlEntity;
import com.sqlcopilot.studio.entity.KnowledgeTermEntity;
import com.sqlcopilot.studio.entity.SchemaColumnCacheEntity;
import com.sqlcopilot.studio.entity.SchemaTableCacheEntity;

import java.util.List;

public interface RagIngestionService {

    void ingestSchema(Long connectionId,
                      String databaseName,
                      List<SchemaTableCacheEntity> tableMetaList,
                      List<SchemaColumnCacheEntity> columnMetaList);

    void ingestSqlHistory(QueryHistoryEntity historyEntity);

    void ingestKnowledgeTerm(KnowledgeTermEntity entity);

    void removeKnowledgeTerm(KnowledgeTermEntity entity);

    void ingestKnowledgeExample(KnowledgeExampleSqlEntity entity);

    void removeKnowledgeExample(KnowledgeExampleSqlEntity entity);

    void rebuildKnowledgeVectors(List<KnowledgeTermEntity> terms, List<KnowledgeExampleSqlEntity> examples);

    void removeSchemaTable(Long connectionId, String databaseName, String tableName);
}
