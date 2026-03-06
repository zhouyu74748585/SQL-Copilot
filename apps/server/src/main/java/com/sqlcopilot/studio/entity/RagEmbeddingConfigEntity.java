package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class RagEmbeddingConfigEntity {
    private Long id;
    private String ragEmbeddingModelDir;
    private Integer ragRerankEnabled;
    private String ragRerankModelDir;
    private Long updatedAt;
}
