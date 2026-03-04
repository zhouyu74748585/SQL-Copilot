package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.ai.AiConfigSaveReq;
import com.sqlcopilot.studio.dto.ai.AiConfigVO;
import com.sqlcopilot.studio.dto.ai.AiModelOptionSaveReq;
import com.sqlcopilot.studio.dto.ai.AiModelOptionVO;
import com.sqlcopilot.studio.entity.AiProviderConfigEntity;
import com.sqlcopilot.studio.mapper.AiConfigMapper;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class AiConfigServiceImpl implements AiConfigService {

    private static final long SINGLETON_ID = 1L;
    private static final Pattern OPTION_ID_SAFE_PATTERN = Pattern.compile("[^a-zA-Z0-9_\\-]+");

    private final AiConfigMapper aiConfigMapper;
    private final ObjectMapper objectMapper;

    public AiConfigServiceImpl(AiConfigMapper aiConfigMapper, ObjectMapper objectMapper) {
        this.aiConfigMapper = aiConfigMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiConfigVO getConfig() {
        AiProviderConfigEntity entity = aiConfigMapper.findById(SINGLETON_ID);
        if (entity == null) {
            return defaultConfig();
        }
        List<AiModelOptionVO> options = parseModelOptions(entity.getModelOptionsJson());
        if (options.isEmpty()) {
            options = defaultModelOptions();
        }
        return toVO(options, entity.getUpdatedAt());
    }

    @Override
    public AiConfigVO saveConfig(AiConfigSaveReq req) {
        List<AiModelOptionVO> modelOptions = normalizeModelOptions(req.getModelOptions());
        if (modelOptions.isEmpty()) {
            throw new BusinessException(400, "请至少配置一个模型选项");
        }
        AiModelOptionVO activeOption = selectActiveOption(req.getProviderType(), modelOptions);
        long now = System.currentTimeMillis();

        AiProviderConfigEntity entity = aiConfigMapper.findById(SINGLETON_ID);
        boolean exists = entity != null;
        if (entity == null) {
            entity = new AiProviderConfigEntity();
            entity.setId(SINGLETON_ID);
        }
        // 关键操作：以模型列表作为唯一数据源，旧字段仅同步首选项快照。
        entity.setProviderType(activeOption.getProviderType());
        entity.setOpenaiBaseUrl(activeOption.getOpenaiBaseUrl());
        entity.setOpenaiApiKey(activeOption.getOpenaiApiKey());
        entity.setOpenaiModel(activeOption.getOpenaiModel());
        entity.setCliCommand(activeOption.getCliCommand());
        entity.setCliWorkingDir(activeOption.getCliWorkingDir());
        entity.setModelOptionsJson(serializeModelOptions(modelOptions));
        entity.setUpdatedAt(now);

        if (!exists) {
            aiConfigMapper.insert(entity);
        } else {
            aiConfigMapper.update(entity);
        }
        return toVO(modelOptions, now);
    }

    private AiConfigVO defaultConfig() {
        return toVO(defaultModelOptions(), 0L);
    }

    private AiConfigVO toVO(List<AiModelOptionVO> options, Long updatedAt) {
        AiModelOptionVO first = options.get(0);
        AiConfigVO vo = new AiConfigVO();
        vo.setProviderType(first.getProviderType());
        vo.setOpenaiBaseUrl(first.getOpenaiBaseUrl());
        vo.setOpenaiApiKey(first.getOpenaiApiKey());
        vo.setOpenaiModel(first.getOpenaiModel());
        vo.setCliCommand(first.getCliCommand());
        vo.setCliWorkingDir(first.getCliWorkingDir());
        vo.setModelOptions(options);
        vo.setUpdatedAt(updatedAt == null ? 0L : updatedAt);
        return vo;
    }

    private List<AiModelOptionVO> parseModelOptions(String modelOptionsJson) {
        String raw = safe(modelOptionsJson);
        if (raw.isBlank()) {
            return List.of();
        }
        try {
            List<AiModelOptionVO> options = objectMapper.readValue(raw, new TypeReference<List<AiModelOptionVO>>() {
            });
            List<AiModelOptionSaveReq> reqItems = new ArrayList<>();
            for (AiModelOptionVO option : options) {
                if (option == null) {
                    continue;
                }
                AiModelOptionSaveReq item = new AiModelOptionSaveReq();
                item.setId(option.getId());
                item.setName(option.getName());
                item.setProviderType(option.getProviderType());
                item.setOpenaiBaseUrl(option.getOpenaiBaseUrl());
                item.setOpenaiApiKey(option.getOpenaiApiKey());
                item.setOpenaiModel(option.getOpenaiModel());
                item.setCliCommand(option.getCliCommand());
                item.setCliWorkingDir(option.getCliWorkingDir());
                reqItems.add(item);
            }
            return normalizeModelOptions(reqItems);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<AiModelOptionVO> normalizeModelOptions(List<AiModelOptionSaveReq> requestOptions) {
        if (requestOptions == null || requestOptions.isEmpty()) {
            return List.of();
        }
        List<AiModelOptionVO> options = new ArrayList<>();
        Set<String> idSet = new HashSet<>();
        int index = 1;
        for (AiModelOptionSaveReq item : requestOptions) {
            if (item == null) {
                continue;
            }
            AiModelOptionVO option = new AiModelOptionVO();
            option.setProviderType(normalizeProviderType(item.getProviderType()));
            option.setOpenaiBaseUrl(safe(item.getOpenaiBaseUrl()));
            option.setOpenaiApiKey(safe(item.getOpenaiApiKey()));
            option.setOpenaiModel(safe(item.getOpenaiModel()));
            option.setCliCommand(safe(item.getCliCommand()));
            option.setCliWorkingDir(safe(item.getCliWorkingDir()));
            option.setName(resolveOptionName(safe(item.getName()), option, index));
            option.setId(resolveUniqueOptionId(safe(item.getId()), option, index, idSet));
            validateOption(option);
            options.add(option);
            index++;
        }
        return options;
    }

    private void validateOption(AiModelOptionVO option) {
        if ("OPENAI".equals(option.getProviderType())) {
            if (safe(option.getOpenaiModel()).isBlank()) {
                throw new BusinessException(400, "OpenAI 模型名称不能为空");
            }
            option.setOpenaiBaseUrl(defaultOpenAiBaseUrl(option.getOpenaiBaseUrl()));
            return;
        }
        if (safe(option.getCliCommand()).isBlank()) {
            throw new BusinessException(400, "CLI 命令不能为空");
        }
    }

    private AiModelOptionVO selectActiveOption(String preferredProviderType, List<AiModelOptionVO> options) {
        String preferred = safe(preferredProviderType).toUpperCase();
        if (!preferred.isBlank()) {
            for (AiModelOptionVO option : options) {
                if (preferred.equals(option.getProviderType())) {
                    return option;
                }
            }
        }
        return options.get(0);
    }

    private String serializeModelOptions(List<AiModelOptionVO> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception ex) {
            throw new BusinessException(500, "序列化模型配置失败: " + ex.getMessage());
        }
    }

    private String resolveOptionName(String inputName, AiModelOptionVO option, int index) {
        if (!inputName.isBlank()) {
            return inputName;
        }
        if ("OPENAI".equals(option.getProviderType())) {
            String model = safe(option.getOpenaiModel());
            return model.isBlank() ? "OpenAI-" + index : "OpenAI " + model;
        }
        return "CLI-" + index;
    }

    private String resolveUniqueOptionId(String inputId, AiModelOptionVO option, int index, Set<String> idSet) {
        String normalized = normalizeOptionId(inputId);
        if (normalized.isBlank()) {
            normalized = normalizeOptionId(option.getProviderType().toLowerCase() + "-" + index);
        }
        String unique = normalized;
        int suffix = 2;
        while (idSet.contains(unique)) {
            unique = normalized + "-" + suffix;
            suffix++;
        }
        idSet.add(unique);
        return unique;
    }

    private String normalizeOptionId(String raw) {
        String value = safe(raw);
        if (value.isBlank()) {
            return "";
        }
        value = OPTION_ID_SAFE_PATTERN.matcher(value).replaceAll("-");
        while (value.contains("--")) {
            value = value.replace("--", "-");
        }
        return value.replaceAll("^-+", "").replaceAll("-+$", "");
    }

    private List<AiModelOptionVO> defaultModelOptions() {
        AiModelOptionVO option = new AiModelOptionVO();
        option.setId("openai-default");
        option.setName("OpenAI gpt-4.1-mini");
        option.setProviderType("OPENAI");
        option.setOpenaiBaseUrl("https://api.openai.com/v1");
        option.setOpenaiApiKey("");
        option.setOpenaiModel("gpt-4.1-mini");
        option.setCliCommand("");
        option.setCliWorkingDir("");
        return List.of(option);
    }

    private String defaultOpenAiBaseUrl(String raw) {
        String value = safe(raw);
        return value.isBlank() ? "https://api.openai.com/v1" : value;
    }

    private String normalizeProviderType(String input) {
        String value = safe(input).toUpperCase();
        if ("OPENAI".equals(value) || "LOCAL_CLI".equals(value)) {
            return value;
        }
        throw new BusinessException(400, "不支持的 AI 接入方式: " + input);
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }
}
