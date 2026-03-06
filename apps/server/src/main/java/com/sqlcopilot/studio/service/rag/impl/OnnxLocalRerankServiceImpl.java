package com.sqlcopilot.studio.service.rag.impl;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
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

    private final boolean rerankEnabled;
    private final String modelDir;
    private final String modelFileName;
    private final int featureSize;
    private final String executionProvider;
    private final int cudaDeviceId;

    private final ReentrantReadWriteLock runtimeLock = new ReentrantReadWriteLock();
    private final Lock readLock = runtimeLock.readLock();
    private final Lock writeLock = runtimeLock.writeLock();

    private volatile boolean initialized;
    private volatile boolean available;
    private volatile String runtimeProvider = "UNAVAILABLE";

    private OrtEnvironment ortEnvironment;
    private OrtSession ortSession;

    public OnnxLocalRerankServiceImpl(@Value("${rag.rerank.enabled:false}") boolean rerankEnabled,
                                      @Value("${rag.rerank.model-dir:./models/rerank}") String modelDir,
                                      @Value("${rag.rerank.model-file-name:rerank.onnx}") String modelFileName,
                                      @Value("${rag.rerank.feature-size:6}") int featureSize,
                                      @Value("${rag.rerank.execution-provider:AUTO}") String executionProvider,
                                      @Value("${rag.rerank.cuda-device-id:0}") int cudaDeviceId) {
        this.rerankEnabled = rerankEnabled;
        this.modelDir = Objects.toString(modelDir, "").trim();
        this.modelFileName = Objects.toString(modelFileName, "rerank.onnx").trim();
        this.featureSize = Math.max(4, featureSize);
        this.executionProvider = Objects.toString(executionProvider, "AUTO").trim().toUpperCase(Locale.ROOT);
        this.cudaDeviceId = Math.max(0, cudaDeviceId);
    }

    @Override
    public List<Double> score(String query, String bucket, List<QdrantScoredPoint> hits) {
        if (!rerankEnabled || hits == null || hits.isEmpty()) {
            return List.of();
        }
        ensureInitialized();
        if (!available || ortEnvironment == null || ortSession == null) {
            return List.of();
        }

        readLock.lock();
        try {
            long now = System.currentTimeMillis();
            float[][] features = new float[hits.size()][featureSize];
            for (int i = 0; i < hits.size(); i++) {
                QdrantScoredPoint hit = hits.get(i);
                double vectorScore = hit.getScore() == null ? 0.0 : hit.getScore();
                Map<String, Object> payload = hit.getPayload();
                features[i][0] = (float) clip01(vectorScore);
                features[i][1] = (float) schemaExactHit(bucket, payload);
                features[i][2] = (float) queryTimeSignal(query);
                features[i][3] = (float) hitCoverage(query, payload);
                features[i][4] = (float) recencyDecay(payload, now);
                features[i][5] = (float) bucketCode(bucket);
            }
            float[] flat = flatten(features);
            long[] shape = new long[]{features.length, featureSize};
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

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        writeLock.lock();
        try {
            if (initialized) {
                return;
            }
            initialized = true;
            if (!rerankEnabled) {
                available = false;
                runtimeProvider = "DISABLED";
                return;
            }
            Path modelPath = Path.of(modelDir).resolve(modelFileName).normalize();
            if (!Files.exists(modelPath)) {
                available = false;
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
            available = false;
            runtimeProvider = "INIT_FAILED";
            log.warn("[RAG-RERANK-INIT-FAILED] reason={}", ex.getMessage());
            closeQuietly();
        } finally {
            writeLock.unlock();
        }
    }

    private void configureProvider(OrtSession.SessionOptions options) {
        String provider = executionProvider;
        if ("CUDA".equals(provider)) {
            try {
                options.addCUDA(cudaDeviceId);
                runtimeProvider = OrtProvider.CUDA.getName();
                return;
            } catch (Throwable ex) {
                log.warn("[RAG-RERANK-CUDA-UNAVAILABLE] fallback to CPU, reason={}", ex.getMessage());
            }
        }
        runtimeProvider = OrtProvider.CPU.getName();
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

    private float[] flatten(float[][] matrix) {
        float[] flat = new float[matrix.length * featureSize];
        int idx = 0;
        for (float[] row : matrix) {
            for (int j = 0; j < featureSize; j++) {
                flat[idx++] = j < row.length ? row[j] : 0f;
            }
        }
        return flat;
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

    private void closeQuietly() {
        try {
            if (ortSession != null) {
                ortSession.close();
            }
        } catch (Exception ignored) {
        }
        ortSession = null;
        ortEnvironment = null;
    }
}
