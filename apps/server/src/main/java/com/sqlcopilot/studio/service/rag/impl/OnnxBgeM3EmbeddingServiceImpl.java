package com.sqlcopilot.studio.service.rag.impl;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.util.BusinessException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OnnxBgeM3EmbeddingServiceImpl implements RagEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OnnxBgeM3EmbeddingServiceImpl.class);

    private final RagConfigService ragConfigService;
    private final String defaultModelDataPath;
    private final String defaultModelDir;
    private final String defaultModelFileName;
    private final String defaultModelDataFileName;
    private final String defaultTokenizerFileName;
    private final String defaultTokenizerConfigFileName;
    private final String defaultConfigFileName;
    private final String defaultSpecialTokensFileName;
    private final String defaultSentencepieceFileName;
    private final int maxSeqLen;
    private final int fallbackDimension;

    private final Object initLock = new Object();

    private boolean initialized;
    private boolean fallbackOnly;
    private EmbeddingFileConfig loadedConfig;

    private OrtEnvironment ortEnvironment;
    private OrtSession ortSession;
    private HuggingFaceTokenizer tokenizer;

    public OnnxBgeM3EmbeddingServiceImpl(RagConfigService ragConfigService,
                                         @Value("${rag.embedding.model-data-path:}") String defaultModelDataPath,
                                         @Value("${rag.embedding.model-dir:./models/bge-m3}") String defaultModelDir,
                                         @Value("${rag.embedding.model-file-name:model_optimized.onnx}") String defaultModelFileName,
                                         @Value("${rag.embedding.model-data-file-name:model_optimized.onnx.data}") String defaultModelDataFileName,
                                         @Value("${rag.embedding.tokenizer-file-name:tokenizer.json}") String defaultTokenizerFileName,
                                         @Value("${rag.embedding.tokenizer-config-file-name:tokenizer_config.json}") String defaultTokenizerConfigFileName,
                                         @Value("${rag.embedding.config-file-name:config.json}") String defaultConfigFileName,
                                         @Value("${rag.embedding.special-tokens-file-name:special_tokens_map.json}") String defaultSpecialTokensFileName,
                                         @Value("${rag.embedding.sentencepiece-file-name:sentencepiece.bpe.model}") String defaultSentencepieceFileName,
                                         @Value("${rag.embedding.max-seq-len:512}") int maxSeqLen,
                                         @Value("${rag.embedding.fallback-dimension:1024}") int fallbackDimension) {
        this.ragConfigService = ragConfigService;
        this.defaultModelDataPath = defaultModelDataPath;
        this.defaultModelDir = defaultModelDir;
        this.defaultModelFileName = defaultModelFileName;
        this.defaultModelDataFileName = defaultModelDataFileName;
        this.defaultTokenizerFileName = defaultTokenizerFileName;
        this.defaultTokenizerConfigFileName = defaultTokenizerConfigFileName;
        this.defaultConfigFileName = defaultConfigFileName;
        this.defaultSpecialTokensFileName = defaultSpecialTokensFileName;
        this.defaultSentencepieceFileName = defaultSentencepieceFileName;
        this.maxSeqLen = Math.max(16, maxSeqLen);
        this.fallbackDimension = Math.max(128, fallbackDimension);
    }

    @Override
    public List<Float> embedText(String text) {
        String normalizedText = text == null ? "" : text;
        ensureInitialized();

        if (fallbackOnly) {
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

    private void ensureInitialized() {
        EmbeddingFileConfig targetConfig = resolveEmbeddingFileConfig();
        synchronized (initLock) {
            if (initialized && targetConfig.equals(loadedConfig)) {
                return;
            }

            closeRuntimeQuietly();
            initialized = true;
            loadedConfig = targetConfig;

            try {
                if (!Files.exists(targetConfig.modelPath()) || !Files.exists(targetConfig.tokenizerPath())) {
                    fallbackOnly = true;
                    log.warn("未找到 ONNX 模型或 tokenizer，启用哈希向量降级。model={}, tokenizer={}",
                        targetConfig.modelPath(), targetConfig.tokenizerPath());
                    return;
                }

                logOptionalFiles(targetConfig);
                Path modelForSession = prepareModelPathForSession(targetConfig.modelPath(), targetConfig.modelDataPath());
                ortEnvironment = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
                ortSession = ortEnvironment.createSession(modelForSession.toAbsolutePath().toString(), options);
                tokenizer = HuggingFaceTokenizer.newInstance(targetConfig.tokenizerPath());
                fallbackOnly = false;

                log.info("ONNX 向量服务初始化完成，source={}, model={}, tokenizer={}",
                    targetConfig.sourceDescription(),
                    modelForSession.toAbsolutePath(),
                    targetConfig.tokenizerPath().toAbsolutePath());
            } catch (Exception ex) {
                fallbackOnly = true;
                log.warn("初始化 ONNX 向量服务失败，启用哈希向量降级，原因={}", ex.getMessage());
            }
        }
    }

    /**
     * 关键逻辑：优先使用前端配置的模型目录（支持 clone 仓库目录结构），否则回退到单文件路径配置。
     */
    private EmbeddingFileConfig resolveEmbeddingFileConfig() {
        RagConfigVO config = ragConfigService.getConfig();

        String configuredModelDir = safeTrim(config.getRagEmbeddingModelDir());
        String configuredModelPath = safeTrim(config.getRagEmbeddingModelPath());
        String modelFileName = nonBlankOrDefault(config.getRagEmbeddingModelFileName(), defaultModelFileName);
        String modelDataFileName = nonBlankOrDefault(config.getRagEmbeddingModelDataFileName(), defaultModelDataFileName);
        String tokenizerFileName = nonBlankOrDefault(config.getRagEmbeddingTokenizerFileName(), defaultTokenizerFileName);
        String tokenizerConfigFileName = nonBlankOrDefault(
            config.getRagEmbeddingTokenizerConfigFileName(),
            defaultTokenizerConfigFileName
        );
        String configFileName = nonBlankOrDefault(config.getRagEmbeddingConfigFileName(), defaultConfigFileName);
        String specialTokensFileName = nonBlankOrDefault(
            config.getRagEmbeddingSpecialTokensFileName(),
            defaultSpecialTokensFileName
        );
        String sentencepieceFileName = nonBlankOrDefault(
            config.getRagEmbeddingSentencepieceFileName(),
            defaultSentencepieceFileName
        );

        if (!isBlank(configuredModelDir)) {
            Path modelDirPath = Path.of(configuredModelDir).toAbsolutePath();
            Path modelPath = resolveModelFileByName(modelDirPath, modelFileName);
            Path tokenizerPath = resolveTokenizerPath(modelDirPath, tokenizerFileName);
            Path modelDataPath = resolveOptionalPathFromDir(modelDirPath, modelDataFileName, defaultModelDataFileName);
            OptionalFileStatus optionalFileStatus = resolveOptionalFileStatus(
                modelDirPath,
                tokenizerConfigFileName,
                configFileName,
                specialTokensFileName,
                sentencepieceFileName
            );
            return new EmbeddingFileConfig(
                modelPath,
                tokenizerPath,
                modelDataPath,
                optionalFileStatus.existingFiles(),
                optionalFileStatus.missingFiles(),
                "model-dir=" + modelDirPath
            );
        }

        if (!isBlank(configuredModelPath)) {
            Path modelPath = Path.of(configuredModelPath).toAbsolutePath();
            Path modelBaseDir = modelPath.getParent() == null ? Path.of(".").toAbsolutePath() : modelPath.getParent().toAbsolutePath();
            Path tokenizerPath = resolveTokenizerPath(modelBaseDir, tokenizerFileName);
            OptionalFileStatus optionalFileStatus = resolveOptionalFileStatus(
                modelBaseDir,
                tokenizerConfigFileName,
                configFileName,
                specialTokensFileName,
                sentencepieceFileName
            );

            String configuredDataPath = safeTrim(config.getRagEmbeddingModelDataPath());
            Path modelDataPath = null;
            if (!isBlank(configuredDataPath)) {
                modelDataPath = Path.of(configuredDataPath).toAbsolutePath();
            } else {
                modelDataPath = resolveOptionalPathFromDir(modelBaseDir, modelDataFileName, defaultModelDataFileName);
                if (modelDataPath == null && !isBlank(defaultModelDataPath)) {
                    Path fallbackDataPath = Path.of(defaultModelDataPath).toAbsolutePath();
                    if (Files.exists(fallbackDataPath)) {
                        modelDataPath = fallbackDataPath;
                    }
                }
            }
            return new EmbeddingFileConfig(
                modelPath,
                tokenizerPath,
                modelDataPath,
                optionalFileStatus.existingFiles(),
                optionalFileStatus.missingFiles(),
                "model-path=" + modelPath
            );
        }

        Path defaultModelDirPath = Path.of(defaultModelDir).toAbsolutePath();
        Path defaultModelPath = resolveModelFileByName(defaultModelDirPath, defaultModelFileName);
        Path defaultTokenizerPath = resolveTokenizerPath(defaultModelDirPath, tokenizerFileName);
        Path defaultModelDataPathResolved = resolveOptionalPathFromDir(
            defaultModelDirPath,
            modelDataFileName,
            defaultModelDataFileName
        );
        OptionalFileStatus optionalFileStatus = resolveOptionalFileStatus(
            defaultModelDirPath,
            tokenizerConfigFileName,
            configFileName,
            specialTokensFileName,
            sentencepieceFileName
        );
        return new EmbeddingFileConfig(
            defaultModelPath,
            defaultTokenizerPath,
            defaultModelDataPathResolved,
            optionalFileStatus.existingFiles(),
            optionalFileStatus.missingFiles(),
            "default-model-dir=" + defaultModelDirPath
        );
    }

    private Path resolveModelFileByName(Path modelDirPath, String configuredFileName) {
        if (!isBlank(configuredFileName)) {
            Path configured = resolvePath(modelDirPath, configuredFileName);
            if (Files.exists(configured)) {
                return configured;
            }
        }

        Path optimized = modelDirPath.resolve("model_optimized.onnx");
        if (Files.exists(optimized)) {
            return optimized;
        }

        Path defaultModel = modelDirPath.resolve("model.onnx");
        if (Files.exists(defaultModel)) {
            return defaultModel;
        }

        return resolvePath(modelDirPath, configuredFileName);
    }

    private Path resolveTokenizerPath(Path baseDir, String tokenizerFileName) {
        if (!isBlank(tokenizerFileName)) {
            Path configuredTokenizerPath = resolvePath(baseDir, tokenizerFileName);
            if (Files.exists(configuredTokenizerPath)) {
                return configuredTokenizerPath;
            }
        }
        Path directoryDefaultTokenizer = baseDir.resolve(defaultTokenizerFileName).toAbsolutePath();
        if (Files.exists(directoryDefaultTokenizer)) {
            return directoryDefaultTokenizer;
        }
        return directoryDefaultTokenizer;
    }

    private Path resolveOptionalPathFromDir(Path baseDir, String configuredName, String defaultName) {
        String candidateName = nonBlankOrDefault(configuredName, defaultName);
        if (isBlank(candidateName)) {
            return null;
        }
        Path candidatePath = resolvePath(baseDir, candidateName);
        return Files.exists(candidatePath) ? candidatePath : null;
    }

    private OptionalFileStatus resolveOptionalFileStatus(Path baseDir,
                                                         String tokenizerConfigFileName,
                                                         String configFileName,
                                                         String specialTokensFileName,
                                                         String sentencepieceFileName) {
        Map<String, Path> existingFiles = new LinkedHashMap<>();
        List<String> missingFiles = new ArrayList<>();

        collectOptionalFile(existingFiles, missingFiles, "tokenizer_config.json",
            resolveOptionalPathFromDir(baseDir, tokenizerConfigFileName, defaultTokenizerConfigFileName));
        collectOptionalFile(existingFiles, missingFiles, "config.json",
            resolveOptionalPathFromDir(baseDir, configFileName, defaultConfigFileName));
        collectOptionalFile(existingFiles, missingFiles, "special_tokens_map.json",
            resolveOptionalPathFromDir(baseDir, specialTokensFileName, defaultSpecialTokensFileName));
        collectOptionalFile(existingFiles, missingFiles, "sentencepiece.bpe.model",
            resolveOptionalPathFromDir(baseDir, sentencepieceFileName, defaultSentencepieceFileName));

        return new OptionalFileStatus(existingFiles, missingFiles);
    }

    private void collectOptionalFile(Map<String, Path> existingFiles,
                                     List<String> missingFiles,
                                     String logicalName,
                                     Path resolvedPath) {
        if (resolvedPath == null) {
            missingFiles.add(logicalName);
            return;
        }
        existingFiles.put(logicalName, resolvedPath);
    }

    private Path resolvePath(Path baseDir, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = baseDir.resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    private Path prepareModelPathForSession(Path modelPath, Path modelDataPath) {
        if (modelDataPath == null || !Files.exists(modelDataPath)) {
            return modelPath;
        }

        String expectedDataFileName = modelPath.getFileName() + ".data";
        Path expectedDataPath = modelPath.getParent().resolve(expectedDataFileName);
        if (expectedDataPath.equals(modelDataPath)) {
            return modelPath;
        }

        try {
            Path tempDir = Files.createTempDirectory("sqlcopilot-onnx-" + UUID.randomUUID());
            Path tempModelPath = tempDir.resolve(modelPath.getFileName());
            Path tempDataPath = tempDir.resolve(expectedDataFileName);
            Files.copy(modelPath, tempModelPath, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(modelDataPath, tempDataPath, StandardCopyOption.REPLACE_EXISTING);
            return tempModelPath;
        } catch (Exception ex) {
            throw new BusinessException(500, "准备 ONNX 外部数据文件失败: " + ex.getMessage());
        }
    }

    private void logOptionalFiles(EmbeddingFileConfig config) {
        for (Map.Entry<String, Path> entry : config.optionalFiles().entrySet()) {
            log.info("检测到 RAG 附加配置文件，source={}, file={}, path={}",
                config.sourceDescription(), entry.getKey(), entry.getValue());
        }
        if (!config.missingOptionalFiles().isEmpty()) {
            log.info("RAG 附加配置文件缺失（不影响 ONNX 推理），source={}, missing={}",
                config.sourceDescription(), config.missingOptionalFiles());
        }
    }

    private void closeRuntimeQuietly() {
        if (ortSession != null) {
            try {
                ortSession.close();
            } catch (Exception ignore) {
            }
            ortSession = null;
        }
        tokenizer = null;
        ortEnvironment = null;
    }

    private boolean acceptTokenTypeInput() {
        try {
            return ortSession != null && ortSession.getInputInfo().containsKey("token_type_ids");
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

    private String nonBlankOrDefault(String value, String defaultValue) {
        return isBlank(value) ? Objects.toString(defaultValue, "").trim() : value.trim();
    }

    private String safeTrim(String value) {
        return Objects.toString(value, "").trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record OptionalFileStatus(Map<String, Path> existingFiles, List<String> missingFiles) {
    }

    private record EmbeddingFileConfig(Path modelPath,
                                       Path tokenizerPath,
                                       Path modelDataPath,
                                       Map<String, Path> optionalFiles,
                                       List<String> missingOptionalFiles,
                                       String sourceDescription) {
    }
}
