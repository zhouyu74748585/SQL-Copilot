package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class RagEmbeddingConfigEntity {
    private Long id;
    private String ragEmbeddingModelDir;
    private String ragEmbeddingModelFileName;
    private String ragEmbeddingModelDataFileName;
    private String ragEmbeddingTokenizerFileName;
    private String ragEmbeddingTokenizerConfigFileName;
    private String ragEmbeddingConfigFileName;
    private String ragEmbeddingSpecialTokensFileName;
    private String ragEmbeddingSentencepieceFileName;
    private String ragEmbeddingModelPath;
    private String ragEmbeddingModelDataPath;
    private Long updatedAt;
}
