package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.editor.ExportReq;
import com.sqlcopilot.studio.dto.editor.ExportResultVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.mapper.ErGraphSnapshotMapper;
import com.sqlcopilot.studio.mapper.QueryHistoryMapper;
import com.sqlcopilot.studio.mapper.SavedQueryMapper;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EditorServiceImplExportTest {

    @TempDir
    Path tempDir;

    @Mock
    private QueryHistoryMapper queryHistoryMapper;

    @Mock
    private SavedQueryMapper savedQueryMapper;

    @Mock
    private ErGraphSnapshotMapper erGraphSnapshotMapper;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private QdrantClientService qdrantClientService;

    @Mock
    private Connection connection;

    @Mock
    private Statement useStatement;

    @Mock
    private Statement queryStatement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private ResultSetMetaData resultSetMetaData;

    @Test
    void exportResult_appliesMysqlDatabaseContextBeforeCreatingQueryStatement() throws Exception {
        EditorServiceImpl service = new EditorServiceImpl(
            queryHistoryMapper,
            savedQueryMapper,
            erGraphSnapshotMapper,
            connectionService,
            new ObjectMapper(),
            qdrantClientService,
            "sql_history"
        );

        ConnectionEntity entity = new ConnectionEntity();
        entity.setId(1L);
        entity.setDbType("MYSQL");
        entity.setDatabaseName("");

        ExportReq req = new ExportReq();
        req.setConnectionId(1L);
        req.setDatabaseName("demo");
        req.setSqlText("select * from users");
        req.setFormat("csv");
        req.setFileName("editor-export-test-" + UUID.randomUUID());
        req.setExportDirectory(tempDir.toString());

        when(connectionService.getConnectionEntity(1L)).thenReturn(entity);
        when(connectionService.openTargetConnection(1L)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(useStatement, queryStatement);
        when(useStatement.execute("USE `demo`")).thenReturn(true);
        when(queryStatement.executeQuery("select * from users")).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
        when(resultSetMetaData.getColumnCount()).thenReturn(1);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(resultSet.next()).thenReturn(false);

        ExportResultVO result = service.exportResult(req);

        Path exportedFile = Path.of(result.getFilePath());
        try {
            assertTrue(Files.exists(exportedFile));
            assertTrue(result.getSuccess());
            assertEquals("导出成功", result.getMessage());
            assertEquals(tempDir.toAbsolutePath().normalize(), exportedFile.getParent().toAbsolutePath().normalize());

            InOrder inOrder = inOrder(connection, useStatement, queryStatement);
            inOrder.verify(connection).setCatalog("demo");
            inOrder.verify(connection).createStatement();
            inOrder.verify(useStatement).execute("USE `demo`");
            inOrder.verify(connection).createStatement();
            inOrder.verify(queryStatement).setFetchSize(Integer.MIN_VALUE);
            inOrder.verify(queryStatement).executeQuery("select * from users");

            verify(resultSetMetaData).getColumnLabel(1);
        } finally {
            Files.deleteIfExists(exportedFile);
        }
    }
}
