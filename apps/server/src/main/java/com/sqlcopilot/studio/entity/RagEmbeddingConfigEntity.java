package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class RagEmbeddingConfigEntity {
    private Long id;
    private String ragEmbeddingModelDir;
    private Long updatedAt;
}
