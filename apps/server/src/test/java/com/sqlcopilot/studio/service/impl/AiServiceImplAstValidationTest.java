package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.ai.AiGenerateSqlReq;
import com.sqlcopilot.studio.dto.schema.SchemaOverviewVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.PromptBudgetPlanner;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.TokenEstimatorService;
import com.sqlcopilot.studio.service.llm.LlmGatewayService;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceImplAstValidationTest {

    @Mock
    private SchemaService schemaService;

    @Mock
    private AiConfigService aiConfigService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private RagRetrievalService ragRetrievalService;

    @Mock
    private LlmGatewayService llmGatewayService;

    @Mock
    private AiConversationContextManager conversationContextManager;

    private TokenEstimatorService tokenEstimatorService;

    private AiServiceImpl aiService;

    @BeforeEach
    void setUp() {
        tokenEstimatorService = new TokenEstimatorService();
        aiService = new AiServiceImpl(
            schemaService,
            aiConfigService,
            connectionService,
            ragRetrievalService,
            new ObjectMapper(),
            llmGatewayService,
            conversationContextManager,
            tokenEstimatorService,
            new PromptBudgetPlanner(tokenEstimatorService)
        );
    }

    @Test
    void validateByAst_allowsQualifiedSystemSchemaTable() throws Exception {
        AiGenerateSqlReq req = buildReq("mdm");
        when(schemaService.getOverview(1L, "mdm")).thenReturn(overview("users"));
        when(schemaService.getOverview(1L, "information_schema")).thenReturn(overview());
        when(connectionService.getConnectionEntity(1L)).thenReturn(connection("MYSQL", "mdm"));

        Object result = ReflectionTestUtils.invokeMethod(
            aiService,
            "validateByAst",
            req,
            "SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema = 'mdm'"
        );

        assertTrue(readBoolean(result, "valid"));
    }

    @Test
    void validateByAst_checksQualifiedNonSystemSchemaTableAgainstReferencedSchema() throws Exception {
        AiGenerateSqlReq req = buildReq("mdm");
        when(schemaService.getOverview(1L, "mdm")).thenReturn(overview("users"));
        when(schemaService.getOverview(1L, "analytics")).thenReturn(overview("events"));
        when(connectionService.getConnectionEntity(1L)).thenReturn(connection("MYSQL", "mdm"));

        Object result = ReflectionTestUtils.invokeMethod(
            aiService,
            "validateByAst",
            req,
            "SELECT COUNT(*) FROM analytics.events"
        );

        assertTrue(readBoolean(result, "valid"));
    }

    @Test
    void validateByAst_rejectsMissingQualifiedNonSystemSchemaTable() throws Exception {
        AiGenerateSqlReq req = buildReq("mdm");
        when(schemaService.getOverview(1L, "mdm")).thenReturn(overview("users"));
        when(schemaService.getOverview(1L, "analytics")).thenReturn(overview("events"));
        when(connectionService.getConnectionEntity(1L)).thenReturn(connection("MYSQL", "mdm"));

        Object result = ReflectionTestUtils.invokeMethod(
            aiService,
            "validateByAst",
            req,
            "SELECT COUNT(*) FROM analytics.missing_events"
        );

        assertFalse(readBoolean(result, "valid"));
        assertTrue(readString(result, "message").contains("analytics.missing_events"));
    }

    @Test
    void validateByAst_allowsMultipleStatements() throws Exception {
        AiGenerateSqlReq req = buildReq("mdm");
        when(schemaService.getOverview(1L, "mdm")).thenReturn(overview("users", "orders"));
        when(connectionService.getConnectionEntity(1L)).thenReturn(connection("MYSQL", "mdm"));

        Object result = ReflectionTestUtils.invokeMethod(
            aiService,
            "validateByAst",
            req,
            "SELECT * FROM users; UPDATE orders SET status = 'DONE' WHERE id = 1;"
        );

        assertTrue(readBoolean(result, "valid"));
        assertTrue(readString(result, "message").contains("2 条 SQL"));
        assertEquals(
            "SELECT * FROM users;\nUPDATE orders SET status = 'DONE' WHERE id = 1",
            readString(result, "sqlText")
        );
    }

    @Test
    void buildRepairPrompt_keepsOnlyDynamicRepairContext() {
        String prompt = ReflectionTestUtils.invokeMethod(
            aiService,
            "buildRepairPrompt",
            "select * from t_user",
            "Unknown column 'name'"
        );

        assertTrue(prompt.contains("Execution error:"));
        assertTrue(prompt.contains("Unknown column 'name'"));
        assertTrue(prompt.contains("Original SQL:"));
        assertTrue(prompt.contains("select * from t_user"));
        assertFalse(prompt.contains("Return strict JSON"));
        assertFalse(prompt.contains("Repair the failed SQL according to the execution error."));
    }

    private AiGenerateSqlReq buildReq(String databaseName) {
        AiGenerateSqlReq req = new AiGenerateSqlReq();
        req.setConnectionId(1L);
        req.setSessionId("s-1");
        req.setPrompt("test");
        req.setDatabaseName(databaseName);
        return req;
    }

    private ConnectionEntity connection(String dbType, String databaseName) {
        ConnectionEntity entity = new ConnectionEntity();
        entity.setId(1L);
        entity.setDbType(dbType);
        entity.setDatabaseName(databaseName);
        return entity;
    }

    private SchemaOverviewVO overview(String... tables) {
        SchemaOverviewVO overview = new SchemaOverviewVO();
        overview.setTableSummaries(List.of(tables).stream().map(item -> {
            SchemaOverviewVO.TableSummaryVO summary = new SchemaOverviewVO.TableSummaryVO();
            summary.setTableName(item);
            return summary;
        }).toList());
        return overview;
    }

    private boolean readBoolean(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (boolean) method.invoke(target);
    }

    private String readString(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (String) method.invoke(target);
    }
}
