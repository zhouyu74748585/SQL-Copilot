package com.sqlcopilot.studio.service.rag.impl;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.rag.RagRerankService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class OnnxLocalRerankServiceImpl implements RagRerankService {

    private static final Logger log = LoggerFactory.getLogger(OnnxLocalRerankServiceImpl.class);
    private static final long RAG_CONFIG_CACHE_TTL_MS = 10_000L;
    private static final String PROVIDER_AUTO = "AUTO";
    private static final String PROVIDER_CPU = "CPU";
    private static final String PROVIDER_CUDA = "CUDA";

    private final RagConfigService ragConfigService;
    private final boolean defaultRerankEnabled;
    private final String defaultModelDir;
    private final String defaultModelFileName;
    private final String defaultExecutionProvider;
    private final int defaultCudaDeviceId;
    private final int defaultFeatureSize;

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

    public OnnxLocalRerankServiceImpl(RagConfigService ragConfigService,
                                      @Value("${rag.rerank.enabled:false}") boolean defaultRerankEnabled,
                                      @Value("${rag.rerank.model-dir:}") String defaultModelDir,
                                      @Value("${rag.rerank.model-file-name:rerank.onnx}") String defaultModelFileName,
                                      @Value("${rag.rerank.feature-size:6}") int defaultFeatureSize,
                                      @Value("${rag.rerank.execution-provider:AUTO}") String defaultExecutionProvider,
                                      @Value("${rag.rerank.cuda-device-id:0}") int defaultCudaDeviceId) {
        this.ragConfigService = ragConfigService;
        this.defaultRerankEnabled = defaultRerankEnabled;
        this.defaultModelDir = safe(defaultModelDir);
        this.defaultModelFileName = safe(defaultModelFileName);
        this.defaultFeatureSize = Math.max(6, defaultFeatureSize);
        this.defaultExecutionProvider = normalizeExecutionProvider(defaultExecutionProvider);
        this.defaultCudaDeviceId = Math.max(0, defaultCudaDeviceId);
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
        if (!available || ortEnvironment == null || ortSession == null) {
            return List.of();
        }

        readLock.lock();
        try {
            long now = System.currentTimeMillis();
            float[][] features = new float[hits.size()][defaultFeatureSize];
            for (int i = 0; i < hits.size(); i++) {
                fillFeatureRow(features[i], query, bucket, hits.get(i), now);
            }
            float[] flat = flatten(features, defaultFeatureSize);
            long[] shape = new long[]{features.length, defaultFeatureSize};
            String inputName = ortSession.getInputNames().stream().findFirst().orElse("input");

            try (OnnxTensor featureTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(flat), shape)) {
                Map<String, OnnxTensor> feed = new HashMap<>();
                feed.put(inputName, featureTensor);
                try (OrtSession.Result result = ortSession.run(feed)) {
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

            Path modelPath = Path.of(targetConfig.modelDir()).resolve(defaultModelFileName).normalize();
            if (!Files.exists(modelPath)) {
                runtimeProvider = "MODEL_MISSING";
                log.warn("[RAG-RERANK-INIT-SKIP] onnx model missing: {}", modelPath);
                return;
            }

            ortEnvironment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            configureProvider(options);
            ortSession = ortEnvironment.createSession(modelPath.toString(), options);
            available = true;
            log.info("[RAG-RERANK-INIT] provider={}, model={}", runtimeProvider, modelPath);
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

    private void fillFeatureRow(float[] row, String query, String bucket, QdrantScoredPoint hit, long now) {
        double vectorScore = hit.getScore() == null ? 0.0 : hit.getScore();
        Map<String, Object> payload = hit.getPayload();
        if (row.length > 0) {
            row[0] = (float) clip01(vectorScore);
        }
        if (row.length > 1) {
            row[1] = (float) schemaExactHit(bucket, payload);
        }
        if (row.length > 2) {
            row[2] = (float) queryTimeSignal(query);
        }
        if (row.length > 3) {
            row[3] = (float) hitCoverage(query, payload);
        }
        if (row.length > 4) {
            row[4] = (float) recencyDecay(payload, now);
        }
        if (row.length > 5) {
            row[5] = (float) bucketCode(bucket);
        }
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
        return List.of();
    }

    private float[] flatten(float[][] matrix, int featureSize) {
        float[] flat = new float[matrix.length * featureSize];
        int idx = 0;
        for (float[] row : matrix) {
            for (int j = 0; j < featureSize; j++) {
                flat[idx++] = j < row.length ? row[j] : 0f;
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
            // 关键优化：Rerank 高频检索场景下短时缓存配置，避免每次查询 DB。
            cachedRagConfig = ragConfigService.getConfig();
            cachedRagConfigLoadedAt = refreshedNow;
            return cachedRagConfig;
        }
    }

    private double schemaExactHit(String bucket, Map<String, Object> payload) {
        if (payload == null) {
            return 0.0;
        }
        return switch (bucket) {
            case "table" -> blank(payloadString(payload, "table_name")) ? 0.0 : 1.0;
            case "column" -> (!blank(payloadString(payload, "table_name")) && !blank(payloadString(payload, "column_name"))) ? 1.0 : 0.0;
            case "metric_term" -> blank(payloadString(payload, "metric_expression")) ? 0.4 : 1.0;
            case "example_sql", "query_history" -> blank(payloadString(payload, "sql_text")) ? 0.4 : 1.0;
            default -> 0.0;
        };
    }

    private double queryTimeSignal(String query) {
        String normalized = Objects.toString(query, "").toLowerCase(Locale.ROOT);
        return normalized.contains("日") || normalized.contains("周") || normalized.contains("月")
            || normalized.contains("季度") || normalized.contains("year") || normalized.contains("month") ? 1.0 : 0.0;
    }

    private double hitCoverage(String query, Map<String, Object> payload) {
        String q = Objects.toString(query, "").toLowerCase(Locale.ROOT).trim();
        if (q.isBlank() || payload == null) {
            return 0.0;
        }
        StringBuilder doc = new StringBuilder();
        doc.append(payloadString(payload, "table_name")).append(' ')
            .append(payloadString(payload, "column_name")).append(' ')
            .append(payloadString(payload, "term")).append(' ')
            .append(payloadString(payload, "definition")).append(' ')
            .append(payloadString(payload, "sql_text")).append(' ')
            .append(payloadString(payload, "prompt_text"));
        String d = doc.toString().toLowerCase(Locale.ROOT);
        if (d.isBlank()) {
            return 0.0;
        }
        String[] tokens = q.split("\\s+");
        if (tokens.length == 0) {
            return 0.0;
        }
        int matched = 0;
        for (String token : tokens) {
            if (!token.isBlank() && d.contains(token)) {
                matched++;
            }
        }
        return clip01((double) matched / tokens.length);
    }

    private double recencyDecay(Map<String, Object> payload, long nowMs) {
        long ts = asLong(payload == null ? null : payload.get("created_at"));
        if (ts <= 0L) {
            ts = asLong(payload == null ? null : payload.get("executed_at"));
        }
        if (ts <= 0L) {
            return 0.0;
        }
        double days = Math.max(0.0, (nowMs - ts) / 86_400_000.0);
        return Math.exp(-days / 90.0);
    }

    private double bucketCode(String bucket) {
        return switch (bucket) {
            case "table" -> 0.1;
            case "column" -> 0.2;
            case "metric_term" -> 0.3;
            case "example_sql" -> 0.4;
            case "query_history" -> 0.5;
            default -> 0.0;
        };
    }

    private String payloadString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        return Objects.toString(payload.get(key), "").trim();
    }

    private long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(Objects.toString(value, "").trim());
        } catch (Exception ignored) {
            return 0L;
        }
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

    private boolean blank(String text) {
        return text == null || text.trim().isEmpty();
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
        try {
            if (ortSession != null) {
                ortSession.close();
            }
        } catch (Exception ignored) {
        }
        ortSession = null;
        ortEnvironment = null;
    }

    private record RerankRuntimeConfig(boolean enabled,
                                       String modelDir) {
    }
}
