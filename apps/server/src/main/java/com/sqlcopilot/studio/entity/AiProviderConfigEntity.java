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
    private Long updatedAt;
}
