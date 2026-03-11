package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class AiProviderConfigEntity {
    private Long id;
    private String providerType;
    private String openaiBaseUrl;
    private String openaiApiKey;
    private String openaiModel;
    private String cliCommand;
    private String cliWorkingDir;
    private String modelOptionsJson;
    private Integer conversationMemoryEnabled;
    private Integer conversationMemoryWindowSize;
    private Integer conversationMemoryWindowTokens;
    private Double conversationAutoCompressRatio;
    private Integer detailOutputEnabled;
    private Long updatedAt;
}
