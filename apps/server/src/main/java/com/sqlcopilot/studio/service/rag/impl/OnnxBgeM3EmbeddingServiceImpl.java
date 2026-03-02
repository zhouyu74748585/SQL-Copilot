package com.sqlcopilot.studio.service.rag.impl;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.util.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class OnnxBgeM3EmbeddingServiceImpl implements RagEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OnnxBgeM3EmbeddingServiceImpl.class);

    private final String modelPath;
    private final String tokenizerPath;
    private final int maxSeqLen;
    private final int fallbackDimension;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean fallbackOnly = new AtomicBoolean(false);

    private OrtEnvironment ortEnvironment;
    private OrtSession ortSession;
    private HuggingFaceTokenizer tokenizer;

    public OnnxBgeM3EmbeddingServiceImpl(@Value("${rag.embedding.model-path:./models/bge-m3/model.onnx}") String modelPath,
                                         @Value("${rag.embedding.tokenizer-path:./models/bge-m3/tokenizer.json}") String tokenizerPath,
                                         @Value("${rag.embedding.max-seq-len:512}") int maxSeqLen,
                                         @Value("${rag.embedding.fallback-dimension:1024}") int fallbackDimension) {
        this.modelPath = modelPath;
        this.tokenizerPath = tokenizerPath;
        this.maxSeqLen = Math.max(16, maxSeqLen);
        this.fallbackDimension = Math.max(128, fallbackDimension);
    }

    @Override
    public List<Float> embedText(String text) {
        String normalizedText = text == null ? "" : text;
        ensureInitialized();

        if (fallbackOnly.get()) {
            return hashEmbedding(normalizedText, fallbackDimension);
        }

        try {
            Encoding encoding = tokenizer.encode(normalizedText);
            long[] inputIds = clipAndPad(encoding.getIds());
            long[] attentionMask = clipAndPad(encoding.getAttentionMask());
            long[] tokenTypeIds = new long[inputIds.length];

            long[][] inputIdsBatch = new long[][]{inputIds};
            long[][] attentionBatch = new long[][]{attentionMask};
            long[][] tokenTypeBatch = new long[][]{tokenTypeIds};

            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnvironment,
                     LongBuffer.wrap(flatten(inputIdsBatch)), new long[]{1, inputIds.length});
                 OnnxTensor attentionTensor = OnnxTensor.createTensor(ortEnvironment,
                     LongBuffer.wrap(flatten(attentionBatch)), new long[]{1, attentionMask.length});
                 OnnxTensor tokenTypeTensor = OnnxTensor.createTensor(ortEnvironment,
                     LongBuffer.wrap(flatten(tokenTypeBatch)), new long[]{1, tokenTypeIds.length})) {

                Map<String, OnnxTensor> feed = new HashMap<>();
                feed.put("input_ids", inputIdsTensor);
                feed.put("attention_mask", attentionTensor);
                if (acceptTokenTypeInput()) {
                    feed.put("token_type_ids", tokenTypeTensor);
                }

                try (OrtSession.Result result = ortSession.run(feed)) {
                    Object output = result.get(0).getValue();
                    if (!(output instanceof float[][][] lastHiddenState)) {
                        throw new BusinessException(500, "BGE-M3 输出结构不符合预期");
                    }
                    return meanPooling(lastHiddenState[0], attentionMask);
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "ONNX 向量化失败: " + ex.getMessage());
        }
    }

    private synchronized void ensureInitialized() {
        if (initialized.get()) {
            return;
        }

        try {
            Path model = Path.of(modelPath);
            Path tokenizerFile = Path.of(tokenizerPath);
            if (!Files.exists(model) || !Files.exists(tokenizerFile)) {
                fallbackOnly.set(true);
                initialized.set(true);
                log.warn("未找到 BGE-M3 ONNX 模型或 tokenizer，启用哈希向量降级。model={}, tokenizer={}",
                    modelPath, tokenizerPath);
                return;
            }

            ortEnvironment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            ortSession = ortEnvironment.createSession(model.toAbsolutePath().toString(), options);
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile);

            initialized.set(true);
            log.info("BGE-M3 ONNX 向量服务初始化完成，model={}", model.toAbsolutePath());
        } catch (Exception ex) {
            fallbackOnly.set(true);
            initialized.set(true);
            log.warn("初始化 ONNX 向量服务失败，启用哈希向量降级，原因={}", ex.getMessage());
        }
    }

    private boolean acceptTokenTypeInput() {
        try {
            return ortSession.getInputInfo().containsKey("token_type_ids");
        } catch (Exception ex) {
            return false;
        }
    }

    private long[] clipAndPad(long[] source) {
        if (source == null || source.length == 0) {
            long[] empty = new long[maxSeqLen];
            empty[0] = 101L;
            return empty;
        }

        long[] clipped = new long[maxSeqLen];
        int len = Math.min(source.length, maxSeqLen);
        System.arraycopy(source, 0, clipped, 0, len);
        return clipped;
    }

    private long[] flatten(long[][] matrix) {
        if (matrix.length == 0) {
            return new long[0];
        }
        int row = matrix.length;
        int col = matrix[0].length;
        long[] flat = new long[row * col];
        int idx = 0;
        for (long[] longs : matrix) {
            for (long value : longs) {
                flat[idx++] = value;
            }
        }
        return flat;
    }

    private List<Float> meanPooling(float[][] tokenEmbeddings, long[] attentionMask) {
        if (tokenEmbeddings.length == 0) {
            return Collections.emptyList();
        }

        int hiddenSize = tokenEmbeddings[0].length;
        float[] sum = new float[hiddenSize];
        float denom = 0f;

        for (int i = 0; i < tokenEmbeddings.length && i < attentionMask.length; i++) {
            if (attentionMask[i] <= 0) {
                continue;
            }
            denom += 1f;
            for (int j = 0; j < hiddenSize; j++) {
                sum[j] += tokenEmbeddings[i][j];
            }
        }

        if (denom <= 0) {
            denom = tokenEmbeddings.length;
        }

        double l2 = 0d;
        List<Float> vector = new ArrayList<>(hiddenSize);
        for (int i = 0; i < hiddenSize; i++) {
            float value = sum[i] / denom;
            vector.add(value);
            l2 += value * value;
        }

        l2 = Math.sqrt(l2);
        if (l2 <= 0d) {
            return vector;
        }

        List<Float> normalized = new ArrayList<>(hiddenSize);
        for (Float value : vector) {
            normalized.add((float) (value / l2));
        }
        return normalized;
    }

    /**
     * 关键降级：在模型缺失场景下提供稳定维度向量，保证 RAG 写链路可用。
     */
    private List<Float> hashEmbedding(String text, int dimension) {
        String normalized = text.toLowerCase(Locale.ROOT);
        float[] buffer = new float[dimension];
        for (int i = 0; i < normalized.length(); i++) {
            int code = normalized.charAt(i);
            int slot = Math.abs((code * 31 + i * 17) % dimension);
            buffer[slot] += 1.0f;
        }

        double l2 = 0d;
        for (float value : buffer) {
            l2 += value * value;
        }
        l2 = Math.sqrt(l2);
        if (l2 <= 0d) {
            l2 = 1d;
        }

        List<Float> vector = new ArrayList<>(dimension);
        for (float value : buffer) {
            vector.add((float) (value / l2));
        }
        return vector;
    }
}
