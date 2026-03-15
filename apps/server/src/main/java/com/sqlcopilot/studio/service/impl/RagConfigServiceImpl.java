package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigSaveReq;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.entity.RagEmbeddingConfigEntity;
import com.sqlcopilot.studio.mapper.RagConfigMapper;
import com.sqlcopilot.studio.service.RagConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

@Service
public class RagConfigServiceImpl implements RagConfigService {

    private static final long SINGLETON_ID = 1L;
    private static final String PROVIDER_LOCAL_ONNX = "LOCAL_ONNX";
    private static final String PROVIDER_ONLINE_OPENAI_COMPAT = "ONLINE_OPENAI_COMPAT";

    private final RagConfigMapper ragConfigMapper;
    private final String defaultEmbeddingProviderType;
    private final String defaultEmbeddingModelDir;
    private final String defaultEmbeddingOnlineBaseUrl;
    private final String defaultEmbeddingOnlineApiKey;
    private final String defaultEmbeddingOnlineModel;
    private final boolean defaultRerankEnabled;
    private final String defaultRerankProviderType;
    private final String defaultRerankModelDir;
    private final String defaultRerankOnlineBaseUrl;
    private final String defaultRerankOnlineApiKey;
    private final String defaultRerankOnlineModel;

    public RagConfigServiceImpl(RagConfigMapper ragConfigMapper,
                                @Value("${rag.embedding.provider-type:LOCAL_ONNX}") String defaultEmbeddingProviderType,
                                @Value("${rag.embedding.model-dir:}") String defaultEmbeddingModelDir,
                                @Value("${rag.embedding.online.base-url:https://api.openai.com/v1}") String defaultEmbeddingOnlineBaseUrl,
                                @Value("${rag.embedding.online.api-key:}") String defaultEmbeddingOnlineApiKey,
                                @Value("${rag.embedding.online.model:}") String defaultEmbeddingOnlineModel,
                                @Value("${rag.rerank.enabled:false}") boolean defaultRerankEnabled,
                                @Value("${rag.rerank.provider-type:LOCAL_ONNX}") String defaultRerankProviderType,
                                @Value("${rag.rerank.model-dir:}") String defaultRerankModelDir,
                                @Value("${rag.rerank.online.base-url:https://api.openai.com/v1}") String defaultRerankOnlineBaseUrl,
                                @Value("${rag.rerank.online.api-key:}") String defaultRerankOnlineApiKey,
                                @Value("${rag.rerank.online.model:}") String defaultRerankOnlineModel) {
        this.ragConfigMapper = ragConfigMapper;
        this.defaultEmbeddingProviderType = normalizeProviderType(defaultEmbeddingProviderType, PROVIDER_LOCAL_ONNX);
        this.defaultEmbeddingModelDir = safe(defaultEmbeddingModelDir);
        this.defaultEmbeddingOnlineBaseUrl = safe(defaultEmbeddingOnlineBaseUrl);
        this.defaultEmbeddingOnlineApiKey = safe(defaultEmbeddingOnlineApiKey);
        this.defaultEmbeddingOnlineModel = safe(defaultEmbeddingOnlineModel);
        this.defaultRerankEnabled = defaultRerankEnabled;
        this.defaultRerankProviderType = normalizeProviderType(defaultRerankProviderType, PROVIDER_LOCAL_ONNX);
        this.defaultRerankModelDir = safe(defaultRerankModelDir);
        this.defaultRerankOnlineBaseUrl = safe(defaultRerankOnlineBaseUrl);
        this.defaultRerankOnlineApiKey = safe(defaultRerankOnlineApiKey);
        this.defaultRerankOnlineModel = safe(defaultRerankOnlineModel);
    }

    @Override
    public RagConfigVO getConfig() {
        RagEmbeddingConfigEntity entity = ragConfigMapper.findById(SINGLETON_ID);
        if (entity == null) {
            return defaultConfig();
        }
        return toVO(entity);
    }

    @Override
    public RagConfigVO saveConfig(RagConfigSaveReq req) {
        long now = System.currentTimeMillis();
        RagEmbeddingConfigEntity entity = ragConfigMapper.findById(SINGLETON_ID);
        boolean exists = entity != null;
        if (entity == null) {
            entity = new RagEmbeddingConfigEntity();
            entity.setId(SINGLETON_ID);
        }

        entity.setRagEmbeddingProviderType(normalizeProviderType(req.getRagEmbeddingProviderType(), defaultEmbeddingProviderType));
        entity.setRagEmbeddingModelDir(nonBlankOrDefault(req.getRagEmbeddingModelDir(), defaultEmbeddingModelDir));
        entity.setRagEmbeddingOnlineBaseUrl(nonBlankOrDefault(req.getRagEmbeddingOnlineBaseUrl(), defaultEmbeddingOnlineBaseUrl));
        entity.setRagEmbeddingOnlineApiKey(nonBlankOrDefault(req.getRagEmbeddingOnlineApiKey(), defaultEmbeddingOnlineApiKey));
        entity.setRagEmbeddingOnlineModel(nonBlankOrDefault(req.getRagEmbeddingOnlineModel(), defaultEmbeddingOnlineModel));
        entity.setRagRerankEnabled(safeBooleanFlag(req.getRagRerankEnabled(), defaultRerankEnabled));
        entity.setRagRerankProviderType(normalizeProviderType(req.getRagRerankProviderType(), defaultRerankProviderType));
        entity.setRagRerankModelDir(nonBlankOrDefault(req.getRagRerankModelDir(), defaultRerankModelDir));
        entity.setRagRerankOnlineBaseUrl(nonBlankOrDefault(req.getRagRerankOnlineBaseUrl(), defaultRerankOnlineBaseUrl));
        entity.setRagRerankOnlineApiKey(nonBlankOrDefault(req.getRagRerankOnlineApiKey(), defaultRerankOnlineApiKey));
        entity.setRagRerankOnlineModel(nonBlankOrDefault(req.getRagRerankOnlineModel(), defaultRerankOnlineModel));
        entity.setUpdatedAt(now);

        // 关键操作：RAG 配置独立单例落库，与 LLM 接入配置物理隔离。
        if (exists) {
            ragConfigMapper.update(entity);
        } else {
            ragConfigMapper.insert(entity);
        }
        return toVO(entity);
    }

    private RagConfigVO defaultConfig() {
        RagConfigVO vo = new RagConfigVO();
        vo.setRagEmbeddingProviderType(defaultEmbeddingProviderType);
        vo.setRagEmbeddingModelDir(defaultEmbeddingModelDir);
        vo.setRagEmbeddingOnlineBaseUrl(defaultEmbeddingOnlineBaseUrl);
        vo.setRagEmbeddingOnlineApiKey(defaultEmbeddingOnlineApiKey);
        vo.setRagEmbeddingOnlineModel(defaultEmbeddingOnlineModel);
        vo.setRagRerankEnabled(defaultRerankEnabled);
        vo.setRagRerankProviderType(defaultRerankProviderType);
        vo.setRagRerankModelDir(defaultRerankModelDir);
        vo.setRagRerankOnlineBaseUrl(defaultRerankOnlineBaseUrl);
        vo.setRagRerankOnlineApiKey(defaultRerankOnlineApiKey);
        vo.setRagRerankOnlineModel(defaultRerankOnlineModel);
        vo.setUpdatedAt(0L);
        return vo;
    }

    private RagConfigVO toVO(RagEmbeddingConfigEntity entity) {
        RagConfigVO vo = new RagConfigVO();
        vo.setRagEmbeddingProviderType(normalizeProviderType(entity.getRagEmbeddingProviderType(), defaultEmbeddingProviderType));
        vo.setRagEmbeddingModelDir(nonBlankOrDefault(entity.getRagEmbeddingModelDir(), defaultEmbeddingModelDir));
        vo.setRagEmbeddingOnlineBaseUrl(nonBlankOrDefault(entity.getRagEmbeddingOnlineBaseUrl(), defaultEmbeddingOnlineBaseUrl));
        vo.setRagEmbeddingOnlineApiKey(nonBlankOrDefault(entity.getRagEmbeddingOnlineApiKey(), defaultEmbeddingOnlineApiKey));
        vo.setRagEmbeddingOnlineModel(nonBlankOrDefault(entity.getRagEmbeddingOnlineModel(), defaultEmbeddingOnlineModel));
        vo.setRagRerankEnabled(entity.getRagRerankEnabled() == null
            ? defaultRerankEnabled
            : entity.getRagRerankEnabled() == 1);
        vo.setRagRerankProviderType(normalizeProviderType(entity.getRagRerankProviderType(), defaultRerankProviderType));
        vo.setRagRerankModelDir(nonBlankOrDefault(entity.getRagRerankModelDir(), defaultRerankModelDir));
        vo.setRagRerankOnlineBaseUrl(nonBlankOrDefault(entity.getRagRerankOnlineBaseUrl(), defaultRerankOnlineBaseUrl));
        vo.setRagRerankOnlineApiKey(nonBlankOrDefault(entity.getRagRerankOnlineApiKey(), defaultRerankOnlineApiKey));
        vo.setRagRerankOnlineModel(nonBlankOrDefault(entity.getRagRerankOnlineModel(), defaultRerankOnlineModel));
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private Integer safeBooleanFlag(Boolean value, boolean defaultValue) {
        if (value == null) {
            return defaultValue ? 1 : 0;
        }
        return value ? 1 : 0;
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }

    private String nonBlankOrDefault(String input, String fallback) {
        String normalized = safe(input);
        return normalized.isBlank() ? safe(fallback) : normalized;
    }

    private String normalizeProviderType(String input, String fallback) {
        String value = safe(input).toUpperCase(Locale.ROOT);
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(value)) {
            return value;
        }
        if (PROVIDER_LOCAL_ONNX.equals(value)) {
            return value;
        }
        String fallbackValue = safe(fallback).toUpperCase(Locale.ROOT);
        if (PROVIDER_ONLINE_OPENAI_COMPAT.equals(fallbackValue)) {
            return PROVIDER_ONLINE_OPENAI_COMPAT;
        }
        return PROVIDER_LOCAL_ONNX;
    }
}
