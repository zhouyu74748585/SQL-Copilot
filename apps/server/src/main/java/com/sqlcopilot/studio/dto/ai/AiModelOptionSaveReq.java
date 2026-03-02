package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 模型选项保存对象。 */
@Data
public class AiModelOptionSaveReq {

    /** 选项唯一标识。 */
    private String id;

    /** 展示名称。 */
    private String name;

    /** 选项类型：OPENAI/LOCAL_CLI。 */
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
}
