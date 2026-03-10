package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class RagEmbeddingConfigEntity {
    private Long id;
    private String ragEmbeddingProviderType;
    private String ragEmbeddingModelDir;
    private String ragEmbeddingOnlineBaseUrl;
    private String ragEmbeddingOnlineApiKey;
    private String ragEmbeddingOnlineModel;
    private Integer ragRerankEnabled;
    private String ragRerankProviderType;
    private String ragRerankModelDir;
    private String ragRerankOnlineBaseUrl;
    private String ragRerankOnlineApiKey;
    private String ragRerankOnlineModel;
    private Long updatedAt;
}
