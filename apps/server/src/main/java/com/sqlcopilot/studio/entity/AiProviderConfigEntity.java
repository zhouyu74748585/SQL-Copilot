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
    private String cliArgs;
    private String cliWorkingDir;
    private Long updatedAt;
}
