package com.sqlcopilot.studio.service.rag.impl;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.CoreMLFlags;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.util.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Service
public class OnnxBgeM3EmbeddingServiceImpl implements RagEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OnnxBgeM3EmbeddingServiceImpl.class);
    private static final long RAG_CONFIG_CACHE_TTL_MS = 10_000L;
    private static final String PROVIDER_AUTO = "AUTO";
    private static final String PROVIDER_CPU = "CPU";
    private static final String PROVIDER_CUDA = "CUDA";
    private static final String PROVIDER_DIRECT_ML = "DIRECT_ML";
    private static final String PROVIDER_CORE_ML = "CORE_ML";
    private static final Set<String> SUPPORTED_PROVIDER_CONFIGS = Set.of(
        PROVIDER_AUTO,
        PROVIDER_CPU,
        PROVIDER_CUDA,
        PROVIDER_DIRECT_ML,
        PROVIDER_CORE_ML
    );

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
    private final String configuredExecutionProvider;
    private final int cudaDeviceId;
    private final int directMlDeviceId;
    private final EnumSet<CoreMLFlags> coreMlFlags;

    private final Object initLock = new Object();
    private final Object configCacheLock = new Object();

    private boolean initialized;
    private boolean fallbackOnly;
    private EmbeddingFileConfig loadedConfig;
    private RagConfigVO cachedRagConfig;
    private long cachedRagConfigLoadedAt;

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
                                         @Value("${rag.embedding.fallback-dimension:1024}") int fallbackDimension,
                                         @Value("${rag.embedding.execution-provider:AUTO}") String executionProvider,
                                         @Value("${rag.embedding.cuda-device-id:0}") int cudaDeviceId,
                                         @Value("${rag.embedding.directml-device-id:0}") int directMlDeviceId,
                                         @Value("${rag.embedding.coreml-flags:}") String coreMlFlagsConfig) {
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
        this.configuredExecutionProvider = resolveConfiguredExecutionProvider(executionProvider);
        this.cudaDeviceId = Math.max(0, cudaDeviceId);
        this.directMlDeviceId = Math.max(0, directMlDeviceId);
        this.coreMlFlags = resolveCoreMlFlags(coreMlFlagsConfig);
    }

    @Override
    public List<Float> embedText(String text) {
        List<List<Float>> vectors = embedTexts(List.of(text == null ? "" : text));
        if (vectors.isEmpty()) {
            return Collections.emptyList();
        }
        return vectors.get(0);
    }

    @Override
    public List<List<Float>> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> normalizedTexts = texts.stream()
            .map(item -> item == null ? "" : item)
            .toList();
        ensureInitialized();

        if (fallbackOnly) {
            List<List<Float>> fallbackVectors = new ArrayList<>(normalizedTexts.size());
            for (String text : normalizedTexts) {
                fallbackVectors.add(hashEmbedding(text, fallbackDimension));
            }
            return fallbackVectors;
        }

        try {
            int batchSize = normalizedTexts.size();
            long[][] inputIdsBatch = new long[batchSize][maxSeqLen];
            long[][] attentionBatch = new long[batchSize][maxSeqLen];
            long[][] tokenTypeBatch = new long[batchSize][maxSeqLen];
            for (int i = 0; i < batchSize; i++) {
                Encoding encoding = tokenizer.encode(normalizedTexts.get(i));
                inputIdsBatch[i] = clipAndPad(encoding.getIds());
                attentionBatch[i] = clipAndPad(encoding.getAttentionMask());
            }

            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnvironment,
                LongBuffer.wrap(flatten(inputIdsBatch)), new long[]{batchSize, maxSeqLen});
                 OnnxTensor attentionTensor = OnnxTensor.createTensor(ortEnvironment,
                     LongBuffer.wrap(flatten(attentionBatch)), new long[]{batchSize, maxSeqLen});
                 OnnxTensor tokenTypeTensor = OnnxTensor.createTensor(ortEnvironment,
                     LongBuffer.wrap(flatten(tokenTypeBatch)), new long[]{batchSize, maxSeqLen})) {

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
                    if (lastHiddenState.length < batchSize) {
                        throw new BusinessException(500, "BGE-M3 输出批次数量不符合预期");
                    }
                    List<List<Float>> vectors = new ArrayList<>(batchSize);
                    for (int i = 0; i < batchSize; i++) {
                        vectors.add(meanPooling(lastHiddenState[i], attentionBatch[i]));
                    }
                    return vectors;
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
                String selectedProvider = configureExecutionProvider(options);
                ortSession = ortEnvironment.createSession(modelForSession.toAbsolutePath().toString(), options);
                tokenizer = HuggingFaceTokenizer.newInstance(targetConfig.tokenizerPath());
                fallbackOnly = false;

                log.info("ONNX 向量服务初始化完成，source={}, model={}, tokenizer={}",
                    targetConfig.sourceDescription(),
                    modelForSession.toAbsolutePath(),
                    targetConfig.tokenizerPath().toAbsolutePath());
                log.info("ONNX execution provider selected={}", selectedProvider);
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
        RagConfigVO config = getCachedRagConfig();

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
            // 关键优化：向量化高频 embed 场景下短时缓存配置，避免每次都查询 rag_embedding_config。
            cachedRagConfig = ragConfigService.getConfig();
            cachedRagConfigLoadedAt = refreshedNow;
            return cachedRagConfig;
        }
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

    private String configureExecutionProvider(OrtSession.SessionOptions options) {
        Set<String> availableProviders = resolveAvailableProviders();
        List<String> selectionOrder = resolveProviderSelectionOrder();
        for (String provider : selectionOrder) {
            if (!PROVIDER_CPU.equals(provider) && !availableProviders.contains(provider)) {
                continue;
            }
            if (tryEnableExecutionProvider(options, provider)) {
                log.info("ONNX provider resolved: configured={}, selected={}, available={}, os={}, arch={}",
                    configuredExecutionProvider,
                    provider,
                    availableProviders,
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"));
                return provider;
            }
        }
        log.warn("ONNX provider fallback to CPU: configured={}, available={}, os={}, arch={}",
            configuredExecutionProvider,
            availableProviders,
            System.getProperty("os.name"),
            System.getProperty("os.arch"));
        return PROVIDER_CPU;
    }

    private List<String> resolveProviderSelectionOrder() {
        if (!PROVIDER_AUTO.equals(configuredExecutionProvider)) {
            return List.of(configuredExecutionProvider, PROVIDER_CPU);
        }

        String osName = Objects.toString(System.getProperty("os.name"), "").toLowerCase(Locale.ROOT);
        List<String> providers = new ArrayList<>();
        if (osName.contains("win")) {
            providers.add(PROVIDER_CUDA);
            providers.add(PROVIDER_DIRECT_ML);
        } else if (osName.contains("mac") && isMacArm64()) {
            providers.add(PROVIDER_CORE_ML);
        }
        providers.add(PROVIDER_CPU);
        return providers;
    }

    private boolean tryEnableExecutionProvider(OrtSession.SessionOptions options, String provider) {
        if (PROVIDER_CPU.equals(provider)) {
            return true;
        }
        try {
            if (PROVIDER_CUDA.equals(provider)) {
                options.addCUDA(cudaDeviceId);
                return true;
            }
            if (PROVIDER_DIRECT_ML.equals(provider)) {
                options.addDirectML(directMlDeviceId);
                return true;
            }
            if (PROVIDER_CORE_ML.equals(provider)) {
                if (coreMlFlags.isEmpty()) {
                    options.addCoreML();
                } else {
                    options.addCoreML(coreMlFlags);
                }
                return true;
            }
            return false;
        } catch (Exception ex) {
            log.warn("Enable ONNX provider failed: provider={}, reason={}", provider, ex.getMessage());
            return false;
        }
    }

    private Set<String> resolveAvailableProviders() {
        Set<String> providers = new LinkedHashSet<>();
        try {
            for (OrtProvider provider : OrtEnvironment.getAvailableProviders()) {
                providers.add(normalizeProviderName(provider.name()));
            }
        } catch (Exception ex) {
            log.warn("Read ONNX available providers failed: {}", ex.getMessage());
        }
        if (providers.isEmpty()) {
            providers.add(PROVIDER_CPU);
        }
        return providers;
    }

    private boolean isMacArm64() {
        String arch = Objects.toString(System.getProperty("os.arch"), "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
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

    private String resolveConfiguredExecutionProvider(String provider) {
        String normalized = normalizeProviderName(provider);
        if (!SUPPORTED_PROVIDER_CONFIGS.contains(normalized)) {
            log.warn("Unsupported rag.embedding.execution-provider={}, fallback to AUTO", provider);
            return PROVIDER_AUTO;
        }
        return normalized;
    }

    private EnumSet<CoreMLFlags> resolveCoreMlFlags(String configText) {
        EnumSet<CoreMLFlags> flags = EnumSet.noneOf(CoreMLFlags.class);
        String raw = Objects.toString(configText, "").trim();
        if (raw.isEmpty()) {
            return flags;
        }
        for (String token : raw.split(",")) {
            String normalized = Objects.toString(token, "")
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
            if (normalized.isEmpty()) {
                continue;
            }
            try {
                flags.add(CoreMLFlags.valueOf(normalized));
            } catch (IllegalArgumentException ex) {
                log.warn("Unsupported CoreML flag '{}', ignored", token);
            }
        }
        return flags;
    }

    private String normalizeProviderName(String provider) {
        String normalized = Objects.toString(provider, "").trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.isEmpty()) {
            return PROVIDER_AUTO;
        }
        if ("DIRECTML".equals(normalized) || "DML".equals(normalized)) {
            return PROVIDER_DIRECT_ML;
        }
        if ("CUDA".equals(normalized)) {
            return PROVIDER_CUDA;
        }
        if ("COREML".equals(normalized)) {
            return PROVIDER_CORE_ML;
        }
        return normalized;
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
