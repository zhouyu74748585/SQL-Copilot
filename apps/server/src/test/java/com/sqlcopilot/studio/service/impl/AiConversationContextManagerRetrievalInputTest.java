package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.ai.AiGenerateSqlReq;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.llm.LlmGatewayService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class AiConversationContextManagerRetrievalInputTest {

    @Mock
    private SchemaService schemaService;

    @Mock
    private AiConfigService aiConfigService;

    @Mock
    private QdrantClientService qdrantClientService;

    @Mock
    private RagEmbeddingService ragEmbeddingService;

    @Mock
    private LlmGatewayService llmGatewayService;

    @Test
    void buildRetrievalInputForRag_prefersCompactHintOverVerbosePrompt() {
        AiConversationContextManager manager = new AiConversationContextManager(
            schemaService,
            aiConfigService,
            null,
            ragEmbeddingService,
            qdrantClientService,
            new ObjectMapper(),
            llmGatewayService,
            "sql_history"
        );
        AiGenerateSqlReq req = new AiGenerateSqlReq();
        req.setPrompt("给这张表 distribution_callback_log 生成100w mock数据");
        req.setMemoryEnabled(false);

        String result = manager.buildRetrievalInputForRag(
            req,
            "检索关键词: 生成模拟数据 限定今年1到3月 distribution_callback_log\n重点表: distribution_callback_log\n意图类型: GENERATE_SQL"
        );

        assertEquals(
            "检索关键词: 生成模拟数据 限定今年1到3月 distribution_callback_log\n重点表: distribution_callback_log",
            result
        );
    }
}
