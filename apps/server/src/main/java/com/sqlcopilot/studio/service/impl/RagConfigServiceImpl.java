package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigSaveReq;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.entity.RagEmbeddingConfigEntity;
import com.sqlcopilot.studio.mapper.RagConfigMapper;
import com.sqlcopilot.studio.service.RagConfigService;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class RagConfigServiceImpl implements RagConfigService {

    private static final long SINGLETON_ID = 1L;

    private final RagConfigMapper ragConfigMapper;

    public RagConfigServiceImpl(RagConfigMapper ragConfigMapper) {
        this.ragConfigMapper = ragConfigMapper;
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
        vo.setUpdatedAt(0L);
        return vo;
    }

    private RagConfigVO toVO(RagEmbeddingConfigEntity entity) {
        RagConfigVO vo = new RagConfigVO();
        vo.setRagEmbeddingModelDir(entity.getRagEmbeddingModelDir());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }
}
