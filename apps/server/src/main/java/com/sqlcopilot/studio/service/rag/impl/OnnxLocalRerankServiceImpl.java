package com.sqlcopilot.studio.service.rag.impl;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.rag.LocalRagRerankService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
@ConditionalOnClass(name = "ai.onnxruntime.OrtEnvironment")
@ConditionalOnProperty(value = "sqlcopilot.rag.local-onnx-enabled", havingValue = "true", matchIfMissing = true)
public class OnnxLocalRerankServiceImpl implements LocalRagRerankService {

    private static final Logger log = LoggerFactory.getLogger(OnnxLocalRerankServiceImpl.class);
    private static final long RAG_CONFIG_CACHE_TTL_MS = 10_000L;
    private static final String PROVIDER_AUTO = "AUTO";
    private static final String PROVIDER_CPU = "CPU";
    private static final String PROVIDER_CUDA = "CUDA";

    private final RagConfigService ragConfigService;
    private final boolean defaultRerankEnabled;
    private final String defaultModelDir;
    private final String defaultModelFileName;
    private final String defaultTokenizerFileName;
    private final String defaultExecutionProvider;
    private final int defaultCudaDeviceId;
    private final int maxSeqLen;

    private final ReentrantReadWriteLock runtimeLock = new ReentrantReadWriteLock();
    private final Lock readLock = runtimeLock.readLock();
    private final Lock writeLock = runtimeLock.writeLock();
    private final Object configCacheLock = new Object();

    private volatile boolean initialized;
    private volatile boolean available;
    private volatile String runtimeProvider = "UNAVAILABLE";
    private volatile RerankRuntimeConfig loadedConfig;

    private RagConfigVO cachedRagConfig;
    private long cachedRagConfigLoadedAt;

    private OrtEnvironment ortEnvironment;
    private OrtSession ortSession;
    private HuggingFaceTokenizer tokenizer;

    public OnnxLocalRerankServiceImpl(RagConfigService ragConfigService,
                                      @Value("${rag.rerank.enabled:false}") boolean defaultRerankEnabled,
                                      @Value("${rag.rerank.model-dir:}") String defaultModelDir,
                                      @Value("${rag.rerank.model-file-name:model.onnx}") String defaultModelFileName,
                                      @Value("${rag.rerank.tokenizer-file-name:tokenizer.json}") String defaultTokenizerFileName,
                                      @Value("${rag.rerank.execution-provider:AUTO}") String defaultExecutionProvider,
                                      @Value("${rag.rerank.cuda-device-id:0}") int defaultCudaDeviceId,
                                      @Value("${rag.rerank.max-seq-len:512}") int maxSeqLen) {
        this.ragConfigService = ragConfigService;
        this.defaultRerankEnabled = defaultRerankEnabled;
        this.defaultModelDir = safe(defaultModelDir);
        this.defaultModelFileName = safe(defaultModelFileName);
        this.defaultTokenizerFileName = safe(defaultTokenizerFileName);
        this.defaultExecutionProvider = normalizeExecutionProvider(defaultExecutionProvider);
        this.defaultCudaDeviceId = Math.max(0, defaultCudaDeviceId);
        this.maxSeqLen = Math.max(32, maxSeqLen);
    }

    @Override
    public List<Double> score(String query, String bucket, List<QdrantScoredPoint> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        RerankRuntimeConfig runtimeConfig = resolveRuntimeConfig();
        if (!runtimeConfig.enabled()) {
            return List.of();
        }
        ensureInitialized(runtimeConfig);
        if (!available || ortEnvironment == null || ortSession == null || tokenizer == null) {
            return List.of();
        }

        readLock.lock();
        try {
            OrtSession localSession = ortSession;
            OrtEnvironment localEnvironment = ortEnvironment;
            HuggingFaceTokenizer localTokenizer = tokenizer;
            String normalizedQuery = safe(query);
            int batchSize = hits.size();
            long[][] inputIdsBatch = new long[batchSize][maxSeqLen];
            long[][] attentionBatch = new long[batchSize][maxSeqLen];
            long[][] tokenTypeBatch = new long[batchSize][maxSeqLen];
            for (int i = 0; i < batchSize; i++) {
                String document = buildRerankDocument(bucket, hits.get(i));
                Encoding encoding = encodeTextPair(localTokenizer, normalizedQuery, document);
                inputIdsBatch[i] = clipAndPad(encoding == null ? null : encoding.getIds());
                attentionBatch[i] = clipAndPad(encoding == null ? null : encoding.getAttentionMask());
            }

            Map<String, OnnxTensor> feed = new LinkedHashMap<>();
            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(
                localEnvironment,
                LongBuffer.wrap(flatten(inputIdsBatch)),
                new long[]{batchSize, maxSeqLen}
            );
                 OnnxTensor attentionTensor = OnnxTensor.createTensor(
                     localEnvironment,
                     LongBuffer.wrap(flatten(attentionBatch)),
                     new long[]{batchSize, maxSeqLen}
                 );
                 OnnxTensor tokenTypeTensor = OnnxTensor.createTensor(
                     localEnvironment,
                     LongBuffer.wrap(flatten(tokenTypeBatch)),
                     new long[]{batchSize, maxSeqLen}
                 )) {
                putTensorIfPresent(localSession, feed, "input_ids", inputIdsTensor, true);
                putTensorIfPresent(localSession, feed, "attention_mask", attentionTensor, false);
                putTensorIfPresent(localSession, feed, "token_type_ids", tokenTypeTensor, false);
                try (OrtSession.Result result = localSession.run(feed)) {
                    Object value = result.get(0).getValue();
                    return normalizeScores(value, hits.size());
                }
            }
        } catch (Exception ex) {
            log.warn("[RAG-RERANK-ONNX-FAILED] bucket={}, reason={}", bucket, ex.getMessage());
            return List.of();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public String getRuntimeProvider() {
        return runtimeProvider;
    }

    @PreDestroy
    public void close() {
        writeLock.lock();
        try {
            closeQuietly();
        } finally {
            writeLock.unlock();
        }
    }

    private void ensureInitialized(RerankRuntimeConfig targetConfig) {
        writeLock.lock();
        try {
            if (initialized && targetConfig.equals(loadedConfig)) {
                return;
            }
            initialized = true;
            loadedConfig = targetConfig;
            closeQuietly();

            if (!targetConfig.enabled()) {
                runtimeProvider = "DISABLED";
                return;
            }

            Path modelDir = Path.of(targetConfig.modelDir()).toAbsolutePath().normalize();
            Path modelPath = modelDir.resolve(defaultModelFileName).normalize();
            Path tokenizerPath = modelDir.resolve(defaultTokenizerFileName).normalize();
            if (!Files.exists(modelPath)) {
                runtimeProvider = "MODEL_MISSING";
                log.warn("[RAG-RERANK-INIT-SKIP] onnx model missing: {}", modelPath);
                return;
            }
            if (!Files.exists(tokenizerPath)) {
                runtimeProvider = "TOKENIZER_MISSING";
                log.warn("[RAG-RERANK-INIT-SKIP] tokenizer missing: {}", tokenizerPath);
                return;
            }

            ortEnvironment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            configureProvider(options);
            ortSession = ortEnvironment.createSession(modelPath.toString(), options);
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
            available = tokenizer != null;
            if (!available) {
                runtimeProvider = "TOKENIZER_INIT_FAILED";
                log.warn("[RAG-RERANK-INIT-SKIP] tokenizer init failed: {}", tokenizerPath);
                closeQuietly();
                return;
            }
            log.info("[RAG-RERANK-INIT] provider={}, model={}, tokenizer={}",
                runtimeProvider, modelPath, tokenizerPath);
        } catch (Exception | LinkageError ex) {
            runtimeProvider = "INIT_FAILED";
            log.warn("[RAG-RERANK-INIT-FAILED] reason={}", ex.getMessage());
            closeQuietly();
        } finally {
            writeLock.unlock();
        }
    }

    private void configureProvider(OrtSession.SessionOptions options) {
        String provider = defaultExecutionProvider;
        if (PROVIDER_CUDA.equals(provider)) {
            try {
                options.addCUDA(defaultCudaDeviceId);
                runtimeProvider = OrtProvider.CUDA.getName();
                return;
            } catch (Throwable ex) {
                log.warn("[RAG-RERANK-CUDA-UNAVAILABLE] fallback to CPU, reason={}", ex.getMessage());
            }
        }
        runtimeProvider = OrtProvider.CPU.getName();
    }

    private Encoding encodeTextPair(HuggingFaceTokenizer localTokenizer, String query, String document) throws Exception {
        Method pairEncodeMethod = null;
        try {
            pairEncodeMethod = localTokenizer.getClass().getMethod("encode", String.class, String.class);
        } catch (NoSuchMethodException ignore) {
            // Fall back to single-string encoding below.
        }
        if (pairEncodeMethod != null) {
            Object value = pairEncodeMethod.invoke(localTokenizer, query, document);
            if (value instanceof Encoding encoding) {
                return encoding;
            }
        }
        return localTokenizer.encode(query + "\n" + document);
    }

    private void putTensorIfPresent(OrtSession session,
                                    Map<String, OnnxTensor> feed,
                                    String preferredInputName,
                                    OnnxTensor tensor,
                                    boolean allowFirstFallback) throws Exception {
        if (session == null || tensor == null) {
            return;
        }
        if (session.getInputInfo().containsKey(preferredInputName)) {
            feed.put(preferredInputName, tensor);
            return;
        }
        if (allowFirstFallback && !session.getInputInfo().isEmpty()) {
            String firstInputName = session.getInputNames().stream().findFirst().orElse(preferredInputName);
            feed.put(firstInputName, tensor);
        }
    }

    private String buildRerankDocument(String bucket, QdrantScoredPoint hit) {
        if (hit == null || hit.getPayload() == null) {
            return "";
        }
        Map<String, Object> payload = hit.getPayload();
        return switch (bucket) {
            case "table" -> "table=" + payloadString(payload, "table_name")
                + "\ncomment=" + payloadString(payload, "table_comment")
                + "\ncolumns=" + String.join(",", payloadStringList(payload, "columns"));
            case "column" -> "table=" + payloadString(payload, "table_name")
                + "\ncolumn=" + payloadString(payload, "column_name")
                + "\ntype=" + payloadString(payload, "data_type")
                + "\ncomment=" + payloadString(payload, "column_comment");
            case "metric_term" -> "term=" + payloadString(payload, "term")
                + "\ndefinition=" + payloadString(payload, "definition")
                + "\nexpression=" + payloadString(payload, "metric_expression");
            case "example_sql", "query_history" -> "sql=" + payloadString(payload, "sql_text")
                + "\nsemantic=" + payloadString(payload, "semantic_description")
                + "\nprompt=" + payloadString(payload, "prompt_text")
                + "\ntables=" + String.join(",", payloadStringList(payload, "tables"));
            default -> payload.toString();
        };
    }

    private List<Double> normalizeScores(Object value, int size) {
        List<Double> scores = new ArrayList<>(size);
        if (value instanceof float[][] matrix) {
            for (int i = 0; i < size; i++) {
                float raw = i < matrix.length && matrix[i].length > 0 ? matrix[i][0] : 0f;
                scores.add(clip01(sigmoid(raw)));
            }
            return scores;
        }
        if (value instanceof float[] arr) {
            for (int i = 0; i < size; i++) {
                float raw = i < arr.length ? arr[i] : 0f;
                scores.add(clip01(sigmoid(raw)));
            }
            return scores;
        }
        if (value instanceof double[][] matrix) {
            for (int i = 0; i < size; i++) {
                double raw = i < matrix.length && matrix[i].length > 0 ? matrix[i][0] : 0D;
                scores.add(clip01(sigmoid(raw)));
            }
            return scores;
        }
        if (value instanceof double[] arr) {
            for (int i = 0; i < size; i++) {
                double raw = i < arr.length ? arr[i] : 0D;
                scores.add(clip01(sigmoid(raw)));
            }
            return scores;
        }
        return List.of();
    }

    private long[] clipAndPad(long[] source) {
        long[] clipped = new long[maxSeqLen];
        if (source == null || source.length == 0) {
            return clipped;
        }
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
        for (long[] values : matrix) {
            for (long value : values) {
                flat[idx++] = value;
            }
        }
        return flat;
    }

    private RerankRuntimeConfig resolveRuntimeConfig() {
        RagConfigVO config = getCachedRagConfig();
        boolean enabled = config.getRagRerankEnabled() == null
            ? defaultRerankEnabled
            : config.getRagRerankEnabled();
        String modelDir = nonBlankOrDefault(config.getRagRerankModelDir(), defaultModelDir);
        return new RerankRuntimeConfig(enabled, modelDir);
    }

    private RagConfigVO getCachedRagConfig() {
        long now = System.currentTimeMillis();
        RagConfigVO localCache = cachedRagConfig;
        if (localCache != null && now - cachedRagConfigLoadedAt < RAG_CONFIG_CACHE_TTL_MS) {
            return localCache;
        }
        synchronized (configCacheLock) {
            long refreshedNow = System.currentTimeMillis();
            if (cachedRagConfig != null && refreshedNow - cachedRagConfigLoadedAt < RAG_CONFIG_CACHE_TTL_MS) {
                return cachedRagConfig;
            }
            cachedRagConfig = ragConfigService.getConfig();
            cachedRagConfigLoadedAt = refreshedNow;
            return cachedRagConfig;
        }
    }

    private List<String> payloadStringList(Map<String, Object> payload, String key) {
        if (payload == null || payload.get(key) == null) {
            return List.of();
        }
        Object rawValue = payload.get(key);
        if (!(rawValue instanceof List<?> rawList)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : rawList) {
            String text = Objects.toString(item, "").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    private String payloadString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        return Objects.toString(payload.get(key), "").trim();
    }

    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private double clip01(double x) {
        if (x < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, x);
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }

    private String nonBlankOrDefault(String input, String fallback) {
        String normalized = safe(input);
        return normalized.isBlank() ? safe(fallback) : normalized;
    }

    private String normalizeExecutionProvider(String input) {
        String normalized = safe(input).toUpperCase(Locale.ROOT);
        if (PROVIDER_CUDA.equals(normalized)) {
            return PROVIDER_CUDA;
        }
        if (PROVIDER_CPU.equals(normalized)) {
            return PROVIDER_CPU;
        }
        return PROVIDER_AUTO;
    }

    private void closeQuietly() {
        available = false;
        if (ortSession != null) {
            try {
                ortSession.close();
            } catch (Exception ignore) {
            }
        }
        ortSession = null;
        ortEnvironment = null;
        tokenizer = null;
    }

    private record RerankRuntimeConfig(boolean enabled, String modelDir) {
    }
}
