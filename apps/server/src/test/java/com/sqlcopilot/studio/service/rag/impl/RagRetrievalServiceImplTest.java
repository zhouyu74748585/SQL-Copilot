package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.dto.schema.SchemaOverviewVO;
import com.sqlcopilot.studio.dto.schema.TableDetailVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.RagRerankService;
import com.sqlcopilot.studio.service.rag.model.QdrantPayloadFilter;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceImplTest {

    @Mock
    private RagConfigService ragConfigService;

    @Mock
    private SchemaService schemaService;

    @Mock
    private RagEmbeddingService ragEmbeddingService;

    @Mock
    private QdrantClientService qdrantClientService;

    @Mock
    private RagRerankService ragRerankService;

    @Test
    void retrievePromptContext_supplementsExplicitFocusTableAndUsesCompactQuery() {
        RagConfigVO config = new RagConfigVO();
        config.setRagRerankEnabled(false);
        when(ragConfigService.getConfig()).thenReturn(config);
        when(ragEmbeddingService.embedText(eq("生成模拟数据 限定今年1到3月 distribution_callback_log\n重点表: distribution_callback_log")))
            .thenReturn(List.of(0.1F, 0.2F));

        when(qdrantClientService.searchPoints(eq("schema_table"), anyList(), anyInt(), eq(1L), eq("mdm")))
            .thenReturn(List.of(new QdrantScoredPoint(
                "table-1",
                0.22D,
                Map.of("table_name", "flyway_schema_history", "table_comment", "history")
            )));
        when(qdrantClientService.searchPoints(eq("schema_column"), anyList(), anyInt(), eq(1L), eq("mdm")))
            .thenReturn(List.of());
        when(qdrantClientService.searchPoints(eq("sql_history"), anyList(), anyInt(), eq(1L), eq("mdm")))
            .thenReturn(List.of());
        when(qdrantClientService.searchPointsByFilters(eq("metric_term"), anyList(), anyInt(), anyList()))
            .thenReturn(List.of());
        when(qdrantClientService.searchPointsByFilters(eq("example_sql"), anyList(), anyInt(), anyList()))
            .thenReturn(List.of());
        doAnswer(invocation -> {
            String collection = invocation.getArgument(0, String.class);
            List<QdrantPayloadFilter> filters = invocation.getArgument(3);
            if (!"sql_history".equals(collection)) {
                return List.of();
            }
            boolean tableMatched = filters.stream()
                .anyMatch(filter -> "tables".equals(filter.key()) && "distribution_callback_log".equals(filter.value()));
            if (!tableMatched) {
                return List.of();
            }
            return List.of(new QdrantScoredPoint(
                "history-1",
                0.31D,
                Map.of(
                    "sql_text", "SELECT * FROM distribution_callback_log LIMIT 100",
                    "tables", List.of("distribution_callback_log")
                )
            ));
        }).when(qdrantClientService).searchPointsByFilters(eq("sql_history"), anyList(), anyInt(), anyList());

        SchemaOverviewVO overview = new SchemaOverviewVO();
        SchemaOverviewVO.TableSummaryVO summary = new SchemaOverviewVO.TableSummaryVO();
        summary.setTableName("distribution_callback_log");
        summary.setTableComment("callback log");
        overview.setTableSummaries(List.of(summary));
        when(schemaService.getOverview(1L, "mdm")).thenReturn(overview);

        TableDetailVO tableDetail = new TableDetailVO();
        tableDetail.setTableComment("callback log");
        TableDetailVO.ColumnDetailVO column = new TableDetailVO.ColumnDetailVO();
        column.setColumnName("event_time");
        column.setDataType("datetime");
        column.setColumnComment("event time");
        tableDetail.setColumns(List.of(column));
        when(schemaService.getTableDetail(1L, "mdm", "distribution_callback_log")).thenReturn(tableDetail);

        RagRetrievalServiceImpl service = new RagRetrievalServiceImpl(
            true,
            "schema_table",
            "schema_column",
            "sql_history",
            "metric_term",
            "example_sql",
            "sql_fragment",
            10,
            20,
            8,
            6,
            6,
            false,
            0.65D,
            0.30D,
            0.05D,
            ragConfigService,
            schemaService,
            ragEmbeddingService,
            qdrantClientService,
            ragRerankService
        );

        RagPromptContext context = service.retrievePromptContext(
            1L,
            "mdm",
            """
            给这张表distribution_callback_log生成100w mock数据，需要分不到今年1到3月之间
            补充上下文:
            检索关键词: 生成模拟数据 限定今年1到3月 distribution_callback_log
            重点表: distribution_callback_log
            意图类型: GENERATE_SQL
            """
        );

        assertTrue(context.getRelatedTables().contains("distribution_callback_log"));
        assertTrue(context.getRelatedColumns().contains("distribution_callback_log.event_time"));
        assertFalse(context.getHistorySqlSamples().isEmpty());
        assertTrue(context.getHistorySqlSamples().get(0).contains("distribution_callback_log"));

        ArgumentCaptor<String> embeddingQueryCaptor = ArgumentCaptor.forClass(String.class);
        verify(ragEmbeddingService).embedText(embeddingQueryCaptor.capture());
        assertTrue(embeddingQueryCaptor.getValue().contains("distribution_callback_log"));
        assertFalse(embeddingQueryCaptor.getValue().contains("意图类型"));
    }
    @Test
    void retrievePromptContext_usesResolvedRerankProviderAfterFirstRequest() {
        RagConfigVO config = new RagConfigVO();
        config.setRagRerankEnabled(true);
        when(ragConfigService.getConfig()).thenReturn(config);
        when(ragRerankService.getRuntimeProvider())
            .thenReturn("LOCAL_ONNX_UNAVAILABLE")
            .thenReturn("LOCAL_ONNX_CPUEXECUTIONPROVIDER")
            .thenReturn("LOCAL_ONNX_CPUEXECUTIONPROVIDER");
        when(ragEmbeddingService.embedText(eq("distribution_callback_log\n重点表: distribution_callback_log")))
            .thenReturn(List.of(0.1F, 0.2F));
        when(qdrantClientService.searchPoints(eq("schema_table"), anyList(), anyInt(), eq(1L), eq("mdm")))
            .thenReturn(List.of(new QdrantScoredPoint(
                "table-1",
                0.22D,
                Map.of("table_name", "distribution_callback_log", "table_comment", "callback log")
            )));
        when(qdrantClientService.searchPoints(eq("schema_column"), anyList(), anyInt(), eq(1L), eq("mdm")))
            .thenReturn(List.of());
        when(qdrantClientService.searchPoints(eq("sql_history"), anyList(), anyInt(), eq(1L), eq("mdm")))
            .thenReturn(List.of());
        when(qdrantClientService.searchPointsByFilters(eq("metric_term"), anyList(), anyInt(), anyList()))
            .thenReturn(List.of());
        when(qdrantClientService.searchPointsByFilters(eq("example_sql"), anyList(), anyInt(), anyList()))
            .thenReturn(List.of());
        when(ragRerankService.score(eq("distribution_callback_log\n重点表: distribution_callback_log"), eq("table"), anyList()))
            .thenReturn(List.of(0.91D));

        RagRetrievalServiceImpl service = new RagRetrievalServiceImpl(
            true,
            "schema_table",
            "schema_column",
            "sql_history",
            "metric_term",
            "example_sql",
            "sql_fragment",
            10,
            20,
            8,
            6,
            6,
            false,
            0.65D,
            0.30D,
            0.05D,
            ragConfigService,
            schemaService,
            ragEmbeddingService,
            qdrantClientService,
            ragRerankService
        );

        RagPromptContext context = service.retrievePromptContext(1L, "mdm", "distribution_callback_log");

        assertEquals("LOCAL_ONNX_CPUEXECUTIONPROVIDER", context.getRerankProvider());
        assertEquals(
            "LOCAL_ONNX_CPUEXECUTIONPROVIDER",
            context.getRerankDetails().stream()
                .filter(detail -> "table".equals(detail.get("bucket")))
                .findFirst()
                .map(detail -> detail.get("provider"))
                .orElse("")
        );
    }
}
