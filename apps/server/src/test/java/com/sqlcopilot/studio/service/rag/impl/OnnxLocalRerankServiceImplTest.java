package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnnxLocalRerankServiceImplTest {

    @Test
    void score_acceptsCrossEncoderModelInputs() {
        Path modelDir = Path.of("models/BgeRerankerBaseOnnxO4").toAbsolutePath().normalize();
        Assumptions.assumeTrue(
            Files.exists(modelDir.resolve("model.onnx")) && Files.exists(modelDir.resolve("tokenizer.json")),
            "本地 ONNX rerank 模型不存在，跳过测试"
        );
        RagConfigService ragConfigService = mock(RagConfigService.class);
        RagConfigVO config = new RagConfigVO();
        config.setRagRerankEnabled(true);
        config.setRagRerankModelDir(modelDir.toString());
        when(ragConfigService.getConfig()).thenReturn(config);

        OnnxLocalRerankServiceImpl service = new OnnxLocalRerankServiceImpl(
            ragConfigService,
            true,
            "",
            "model.onnx",
            "tokenizer.json",
            "CPU",
            0,
            512
        );
        try {
            List<Double> scores = service.score(
                "生成模拟数据 限定今年1到3月 distribution_callback_log",
                "table",
                List.of(new QdrantScoredPoint(
                    "table-1",
                    0.7D,
                    Map.of(
                        "table_name", "distribution_callback_log",
                        "table_comment", "callback log",
                        "columns", List.of("event_time", "status", "http_code")
                    )
                ))
            );

            assertEquals(1, scores.size());
            assertTrue(scores.get(0) >= 0.0D && scores.get(0) <= 1.0D);
        } finally {
            service.close();
        }
    }
}
