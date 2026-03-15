package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.rag.LocalRagEmbeddingService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@Primary
public class RagEmbeddingRouterServiceImpl implements RagEmbeddingService {

    private static final String PROVIDER_LOCAL_ONNX = "LOCAL_ONNX";
    private static final String PROVIDER_ONLINE_OPENAI_COMPAT = "ONLINE_OPENAI_COMPAT";

    private final RagConfigService ragConfigService;
    private final LocalRagEmbeddingService localRagEmbeddingService;
    private final OpenAiCompatEmbeddingServiceImpl openAiCompatEmbeddingService;
    private final String defaultProviderType;

    public RagEmbeddingRouterServiceImpl(RagConfigService ragConfigService,
                                         ObjectProvider<LocalRagEmbeddingService> localRagEmbeddingServiceProvider,
                                         OpenAiCompatEmbeddingServiceImpl openAiCompatEmbeddingService,
                                         @Value("${rag.embedding.provider-type:LOCAL_ONNX}") String defaultProviderType) {
        this.ragConfigService = ragConfigService;
        this.localRagEmbeddingService = localRagEmbeddingServiceProvider.getIfAvailable();
        this.openAiCompatEmbeddingService = openAiCompatEmbeddingService;
        this.defaultProviderType = normalizeProviderType(defaultProviderType, PROVIDER_LOCAL_ONNX);
    }

    @Override
    public List<Float> embedText(String text) {
        String normalizedText = text == null ? "" : text;
        return embedTexts(List.of(normalizedText)).get(0);
    }

    @Override
    public List<List<Float>> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String providerType = resolveProviderType();
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(providerType)) {
            return openAiCompatEmbeddingService.embedTexts(texts);
        }
        if (PROVIDER_LOCAL_ONNX.equals(providerType)) {
            if (localRagEmbeddingService == null) {
                throw new BusinessException(400, "当前发布包不支持本地 ONNX 向量化，请切换到在线模式");
            }
            return localRagEmbeddingService.embedTexts(texts);
        }
        throw new BusinessException(400, "不支持的向量化提供方: " + providerType);
    }

    @Override
    public String getRuntimeProvider() {
        String providerType = resolveProviderType();
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(providerType)) {
            return openAiCompatEmbeddingService.getRuntimeProvider();
        }
        if (localRagEmbeddingService == null) {
            return "LOCAL_ONNX_UNAVAILABLE";
        }
        String localProvider = safe(localRagEmbeddingService.getRuntimeProvider()).toUpperCase(Locale.ROOT);
        return "LOCAL_ONNX_" + (localProvider.isBlank() ? "UNKNOWN" : localProvider);
    }

    private String resolveProviderType() {
        RagConfigVO config = ragConfigService.getConfig();
        return normalizeProviderType(config.getRagEmbeddingProviderType(), defaultProviderType);
    }

    private String normalizeProviderType(String input, String fallback) {
        String value = safe(input).toUpperCase(Locale.ROOT);
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(value)) {
            return PROVIDER_ONLINE_OPENAI_COMPAT;
        }
        if (PROVIDER_LOCAL_ONNX.equals(value)) {
            return PROVIDER_LOCAL_ONNX;
        }
        String fallbackValue = safe(fallback).toUpperCase(Locale.ROOT);
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(fallbackValue)
            || localRagEmbeddingService == null) {
            return PROVIDER_ONLINE_OPENAI_COMPAT;
        }
        return PROVIDER_LOCAL_ONNX;
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }
}
