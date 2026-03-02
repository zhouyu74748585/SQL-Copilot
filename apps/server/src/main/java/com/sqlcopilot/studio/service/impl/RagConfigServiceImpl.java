package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigSaveReq;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.entity.RagEmbeddingConfigEntity;
import com.sqlcopilot.studio.mapper.RagConfigMapper;
import com.sqlcopilot.studio.service.RagConfigService;
import java.util.Objects;
import org.springframework.stereotype.Service;

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
        entity.setRagEmbeddingModelFileName(safe(req.getRagEmbeddingModelFileName()));
        entity.setRagEmbeddingModelDataFileName(safe(req.getRagEmbeddingModelDataFileName()));
        entity.setRagEmbeddingTokenizerFileName(safe(req.getRagEmbeddingTokenizerFileName()));
        entity.setRagEmbeddingTokenizerConfigFileName(safe(req.getRagEmbeddingTokenizerConfigFileName()));
        entity.setRagEmbeddingConfigFileName(safe(req.getRagEmbeddingConfigFileName()));
        entity.setRagEmbeddingSpecialTokensFileName(safe(req.getRagEmbeddingSpecialTokensFileName()));
        entity.setRagEmbeddingSentencepieceFileName(safe(req.getRagEmbeddingSentencepieceFileName()));
        entity.setRagEmbeddingModelPath(safe(req.getRagEmbeddingModelPath()));
        entity.setRagEmbeddingModelDataPath(safe(req.getRagEmbeddingModelDataPath()));
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
        vo.setRagEmbeddingModelFileName("model_optimized.onnx");
        vo.setRagEmbeddingModelDataFileName("model_optimized.onnx.data");
        vo.setRagEmbeddingTokenizerFileName("tokenizer.json");
        vo.setRagEmbeddingTokenizerConfigFileName("tokenizer_config.json");
        vo.setRagEmbeddingConfigFileName("config.json");
        vo.setRagEmbeddingSpecialTokensFileName("special_tokens_map.json");
        vo.setRagEmbeddingSentencepieceFileName("sentencepiece.bpe.model");
        vo.setRagEmbeddingModelPath("./models/bge-m3/model.onnx");
        vo.setRagEmbeddingModelDataPath("");
        vo.setUpdatedAt(0L);
        return vo;
    }

    private RagConfigVO toVO(RagEmbeddingConfigEntity entity) {
        RagConfigVO vo = new RagConfigVO();
        vo.setRagEmbeddingModelDir(entity.getRagEmbeddingModelDir());
        vo.setRagEmbeddingModelFileName(entity.getRagEmbeddingModelFileName());
        vo.setRagEmbeddingModelDataFileName(entity.getRagEmbeddingModelDataFileName());
        vo.setRagEmbeddingTokenizerFileName(entity.getRagEmbeddingTokenizerFileName());
        vo.setRagEmbeddingTokenizerConfigFileName(entity.getRagEmbeddingTokenizerConfigFileName());
        vo.setRagEmbeddingConfigFileName(entity.getRagEmbeddingConfigFileName());
        vo.setRagEmbeddingSpecialTokensFileName(entity.getRagEmbeddingSpecialTokensFileName());
        vo.setRagEmbeddingSentencepieceFileName(entity.getRagEmbeddingSentencepieceFileName());
        vo.setRagEmbeddingModelPath(entity.getRagEmbeddingModelPath());
        vo.setRagEmbeddingModelDataPath(entity.getRagEmbeddingModelDataPath());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }
}
