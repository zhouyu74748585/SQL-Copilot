package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.mapper.RagVectorizeStatusMapper;
import com.sqlcopilot.studio.repository.SchemaNamespaceJdbcRepository;
import com.sqlcopilot.studio.repository.SchemaObjectDefinitionJdbcRepository;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.TokenEstimatorService;
import com.sqlcopilot.studio.service.kv.KvRuntimeClientFactory;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
import com.sqlcopilot.studio.support.JdbcDriverResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaServiceImplKvDatabasesTest {

    @Test
    void listDatabasesShouldUseRedisRuntimeFactoryForRedisConnection() {
        ConnectionService connectionService = mock(ConnectionService.class);
        RagIngestionService ragIngestionService = mock(RagIngestionService.class);
        RagVectorizeStatusMapper ragVectorizeStatusMapper = mock(RagVectorizeStatusMapper.class);
        QdrantClientService qdrantClientService = mock(QdrantClientService.class);
        TokenEstimatorService tokenEstimatorService = mock(TokenEstimatorService.class);
        KvRuntimeClientFactory kvRuntimeClientFactory = mock(KvRuntimeClientFactory.class);
        JdbcDriverResolver jdbcDriverResolver = mock(JdbcDriverResolver.class);
        SchemaNamespaceJdbcRepository schemaNamespaceJdbcRepository = mock(SchemaNamespaceJdbcRepository.class);
        SchemaObjectDefinitionJdbcRepository schemaObjectDefinitionJdbcRepository = mock(SchemaObjectDefinitionJdbcRepository.class);

        SchemaServiceImpl service = new SchemaServiceImpl(
            connectionService,
            ragIngestionService,
            ragVectorizeStatusMapper,
            qdrantClientService,
            tokenEstimatorService,
            kvRuntimeClientFactory,
            jdbcDriverResolver,
            schemaNamespaceJdbcRepository,
            schemaObjectDefinitionJdbcRepository,
            "schema_table",
            "schema_column",
            300_000L,
            60_000L
        );

        ConnectionEntity entity = new ConnectionEntity();
        entity.setId(1L);
        entity.setDbType("REDIS");
        entity.setDatabaseName("0");
        when(connectionService.getConnectionEntity(1L)).thenReturn(entity);
        when(kvRuntimeClientFactory.listRedisDatabases(entity, 1L)).thenReturn(List.of("0", "1", "2"));

        List<String> result = service.listDatabases(1L);

        assertThat(result).containsExactly("0", "1", "2");
        verify(kvRuntimeClientFactory).listRedisDatabases(entity, 1L);
    }
}
