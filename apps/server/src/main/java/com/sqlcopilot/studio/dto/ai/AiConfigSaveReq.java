package com.sqlcopilot.studio.dto.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** 保存 AI 接入配置请求对象。 */
@Data
public class AiConfigSaveReq {

    /** 接入方式：OPENAI/LOCAL_CLI。 */
    @NotBlank
    private String providerType;

    /** OpenAI API 基础地址。 */
    private String openaiBaseUrl;

    /** OpenAI API Key。 */
    private String openaiApiKey;

    /** OpenAI 模型名称。 */
    private String openaiModel;

    /** 本地 CLI 可执行命令。 */
    private String cliCommand;

    /** 本地 CLI 执行工作目录。 */
    private String cliWorkingDir;

    /** 可选模型列表（API/CLI 均可配置）。 */
    @Valid
    private List<AiModelOptionSaveReq> modelOptions;
}
