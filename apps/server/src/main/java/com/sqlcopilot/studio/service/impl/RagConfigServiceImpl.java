package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigSaveReq;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.entity.RagEmbeddingConfigEntity;
import com.sqlcopilot.studio.mapper.RagConfigMapper;
import com.sqlcopilot.studio.service.RagConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class RagConfigServiceImpl implements RagConfigService {

    private static final long SINGLETON_ID = 1L;

    private final RagConfigMapper ragConfigMapper;
    private final boolean defaultRerankEnabled;
    private final String defaultRerankModelDir;

    public RagConfigServiceImpl(RagConfigMapper ragConfigMapper,
                                @Value("${rag.rerank.enabled:false}") boolean defaultRerankEnabled,
                                @Value("${rag.rerank.model-dir:}") String defaultRerankModelDir) {
        this.ragConfigMapper = ragConfigMapper;
        this.defaultRerankEnabled = defaultRerankEnabled;
        this.defaultRerankModelDir = safe(defaultRerankModelDir);
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

        entity.setRagEmbeddingModelDir(safe(req.getRagEmbeddingModelDir()));
        entity.setRagRerankEnabled(safeBooleanFlag(req.getRagRerankEnabled(), defaultRerankEnabled));
        entity.setRagRerankModelDir(nonBlankOrDefault(req.getRagRerankModelDir(), defaultRerankModelDir));
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
        vo.setRagEmbeddingModelDir("");
        vo.setRagRerankEnabled(defaultRerankEnabled);
        vo.setRagRerankModelDir(defaultRerankModelDir);
        vo.setUpdatedAt(0L);
        return vo;
    }

    private RagConfigVO toVO(RagEmbeddingConfigEntity entity) {
        RagConfigVO vo = new RagConfigVO();
        vo.setRagEmbeddingModelDir(entity.getRagEmbeddingModelDir());
        vo.setRagRerankEnabled(entity.getRagRerankEnabled() == null
            ? defaultRerankEnabled
            : entity.getRagRerankEnabled() == 1);
        vo.setRagRerankModelDir(nonBlankOrDefault(entity.getRagRerankModelDir(), defaultRerankModelDir));
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
}
