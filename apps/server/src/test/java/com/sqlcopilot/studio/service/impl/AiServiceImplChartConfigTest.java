package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.ai.ChartConfigVO;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SchemaService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class AiServiceImplChartConfigTest {

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

    private AiServiceImpl aiService;

    @BeforeEach
    void setUp() {
        aiService = new AiServiceImpl(
            schemaService,
            aiConfigService,
            connectionService,
            ragRetrievalService,
            new ObjectMapper(),
            llmGatewayService,
            conversationContextManager
        );
    }

    @Test
    void validateChartConfig_allowsGroupedSeriesMode() throws Exception {
        ChartConfigVO config = new ChartConfigVO();
        config.setChartType("TREND");
        config.setXField("month");
        config.setYFields(List.of("sales_amount"));
        config.setSeriesField("product_name");

        Object result = ReflectionTestUtils.invokeMethod(aiService, "validateChartConfig", config);

        assertTrue(readBoolean(result, "valid"));
        assertEquals("ok", readString(result, "message"));
    }

    @Test
    void validateChartConfig_rejectsGroupedSeriesModeWithMultipleYFields() throws Exception {
        ChartConfigVO config = new ChartConfigVO();
        config.setChartType("LINE");
        config.setXField("month");
        config.setYFields(List.of("sales_amount", "profit_amount"));
        config.setSeriesField("product_name");

        Object result = ReflectionTestUtils.invokeMethod(aiService, "validateChartConfig", config);

        assertEquals(false, readBoolean(result, "valid"));
        assertTrue(readString(result, "message").contains("仅支持 1 个 yField"));
    }

    @Test
    void validateChartConfig_keepsLegacyMultiYAxisModeCompatible() throws Exception {
        ChartConfigVO config = new ChartConfigVO();
        config.setChartType("BAR");
        config.setXField("month");
        config.setYFields(List.of("sales_amount", "profit_amount"));

        Object result = ReflectionTestUtils.invokeMethod(aiService, "validateChartConfig", config);

        assertTrue(readBoolean(result, "valid"));
    }

    @Test
    void normalizeChartConfig_trimsSeriesFieldAndDeduplicatesYFields() {
        ChartConfigVO config = new ChartConfigVO();
        config.setChartType("trend");
        config.setXField("month");
        config.setYFields(List.of(" sales_amount ", "sales_amount", "profit_amount"));
        config.setSeriesField(" product_name ");

        ReflectionTestUtils.invokeMethod(aiService, "normalizeChartConfig", config);

        assertEquals("TREND", config.getChartType());
        assertEquals("product_name", config.getSeriesField());
        assertEquals(List.of("sales_amount", "profit_amount"), config.getYFields());
    }

    @Test
    void chartConfigJacksonMapping_supportsCamelCaseXFieldAndYFields() {
        Map<String, Object> payload = Map.of(
            "chartType", "TREND",
            "xField", "stat_month",
            "yFields", List.of("dispatch_count"),
            "seriesField", "model_id",
            "sortField", "stat_month",
            "sortDirection", "ASC"
        );

        ChartConfigVO config = new ObjectMapper().convertValue(payload, ChartConfigVO.class);

        assertEquals("stat_month", config.getXField());
        assertEquals(List.of("dispatch_count"), config.getYFields());
        assertEquals("model_id", config.getSeriesField());
    }

    @Test
    void chartConfigJacksonSerialization_preservesCamelCaseXFieldAndYFields() {
        ChartConfigVO config = new ChartConfigVO();
        config.setChartType("TREND");
        config.setXField("stat_month");
        config.setYFields(List.of("dispatch_count"));

        Map<String, Object> json = new ObjectMapper().convertValue(config, new TypeReference<Map<String, Object>>() {});

        assertEquals("stat_month", json.get("xField"));
        assertEquals(List.of("dispatch_count"), json.get("yFields"));
        assertNotNull(json.get("xField"));
        assertNotNull(json.get("yFields"));
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
