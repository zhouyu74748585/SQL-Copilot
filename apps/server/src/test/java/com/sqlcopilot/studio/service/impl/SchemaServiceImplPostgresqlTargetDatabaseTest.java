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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaServiceImplPostgresqlTargetDatabaseTest {

    @Test
    void listObjectNamesShouldOpenConnectionWithRequestedPostgresqlDatabase() throws Exception {
        ConnectionService connectionService = mock(ConnectionService.class);
        RagIngestionService ragIngestionService = mock(RagIngestionService.class);
        RagVectorizeStatusMapper ragVectorizeStatusMapper = mock(RagVectorizeStatusMapper.class);
        QdrantClientService qdrantClientService = mock(QdrantClientService.class);
        TokenEstimatorService tokenEstimatorService = mock(TokenEstimatorService.class);
        KvRuntimeClientFactory kvRuntimeClientFactory = mock(KvRuntimeClientFactory.class);
        JdbcDriverResolver jdbcDriverResolver = mock(JdbcDriverResolver.class);
        SchemaNamespaceJdbcRepository schemaNamespaceJdbcRepository = mock(SchemaNamespaceJdbcRepository.class);
        SchemaObjectDefinitionJdbcRepository schemaObjectDefinitionJdbcRepository = mock(SchemaObjectDefinitionJdbcRepository.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet resultSet = mock(ResultSet.class);

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
        entity.setDbType("POSTGRESQL");
        entity.setDatabaseName("postgres");
        when(connectionService.getConnectionEntity(1L)).thenReturn(entity);
        when(connectionService.openTargetConnection(1L, "analytics::public")).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("analytics");
        when(metaData.getTables("analytics", "public", "%", new String[]{"TABLE"})).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<String> result = service.listObjectNames(1L, "analytics::public", "tables");

        assertThat(result).isEmpty();
        verify(connectionService).openTargetConnection(1L, "analytics::public");
    }
}
