package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.rag.RagRerankService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@Primary
public class RagRerankRouterServiceImpl implements RagRerankService {

    private static final String PROVIDER_LOCAL_ONNX = "LOCAL_ONNX";
    private static final String PROVIDER_ONLINE_OPENAI_COMPAT = "ONLINE_OPENAI_COMPAT";

    private final RagConfigService ragConfigService;
    private final OnnxLocalRerankServiceImpl onnxLocalRerankService;
    private final OpenAiCompatRerankServiceImpl openAiCompatRerankService;
    private final boolean defaultRerankEnabled;
    private final String defaultProviderType;

    public RagRerankRouterServiceImpl(RagConfigService ragConfigService,
                                      OnnxLocalRerankServiceImpl onnxLocalRerankService,
                                      OpenAiCompatRerankServiceImpl openAiCompatRerankService,
                                      @Value("${rag.rerank.enabled:false}") boolean defaultRerankEnabled,
                                      @Value("${rag.rerank.provider-type:LOCAL_ONNX}") String defaultProviderType) {
        this.ragConfigService = ragConfigService;
        this.onnxLocalRerankService = onnxLocalRerankService;
        this.openAiCompatRerankService = openAiCompatRerankService;
        this.defaultRerankEnabled = defaultRerankEnabled;
        this.defaultProviderType = normalizeProviderType(defaultProviderType, PROVIDER_LOCAL_ONNX);
    }

    @Override
    public List<Double> score(String query, String bucket, List<QdrantScoredPoint> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        if (!isRerankEnabled()) {
            return List.of();
        }
        String providerType = resolveProviderType();
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(providerType)) {
            return openAiCompatRerankService.score(query, bucket, hits);
        }
        if (PROVIDER_LOCAL_ONNX.equals(providerType)) {
            return onnxLocalRerankService.score(query, bucket, hits);
        }
        throw new BusinessException(400, "不支持的 Rerank 提供方: " + providerType);
    }

    @Override
    public String getRuntimeProvider() {
        if (!isRerankEnabled()) {
            return "DISABLED";
        }
        String providerType = resolveProviderType();
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(providerType)) {
            return openAiCompatRerankService.getRuntimeProvider();
        }
        String localProvider = safe(onnxLocalRerankService.getRuntimeProvider()).toUpperCase(Locale.ROOT);
        return "LOCAL_ONNX_" + (localProvider.isBlank() ? "UNKNOWN" : localProvider);
    }

    private boolean isRerankEnabled() {
        RagConfigVO config = ragConfigService.getConfig();
        if (config.getRagRerankEnabled() != null) {
            return config.getRagRerankEnabled();
        }
        return defaultRerankEnabled;
    }

    private String resolveProviderType() {
        RagConfigVO config = ragConfigService.getConfig();
        return normalizeProviderType(config.getRagRerankProviderType(), defaultProviderType);
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
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(fallbackValue)) {
            return PROVIDER_ONLINE_OPENAI_COMPAT;
        }
        return PROVIDER_LOCAL_ONNX;
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }
}
