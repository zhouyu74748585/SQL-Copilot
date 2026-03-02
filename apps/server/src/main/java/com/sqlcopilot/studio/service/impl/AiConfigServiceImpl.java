package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.ai.AiConfigSaveReq;
import com.sqlcopilot.studio.dto.ai.AiConfigVO;
import com.sqlcopilot.studio.entity.AiProviderConfigEntity;
import com.sqlcopilot.studio.mapper.AiConfigMapper;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class AiConfigServiceImpl implements AiConfigService {

    private static final long SINGLETON_ID = 1L;

    private final AiConfigMapper aiConfigMapper;

    public AiConfigServiceImpl(AiConfigMapper aiConfigMapper) {
        this.aiConfigMapper = aiConfigMapper;
    }

    @Override
    public AiConfigVO getConfig() {
        AiProviderConfigEntity entity = aiConfigMapper.findById(SINGLETON_ID);
        if (entity == null) {
            return defaultConfig();
        }
        return toVO(entity);
    }

    @Override
    public AiConfigVO saveConfig(AiConfigSaveReq req) {
        String providerType = normalizeProviderType(req.getProviderType());
        long now = System.currentTimeMillis();

        AiProviderConfigEntity entity = aiConfigMapper.findById(SINGLETON_ID);
        boolean exists = entity != null;
        if (entity == null) {
            entity = new AiProviderConfigEntity();
            entity.setId(SINGLETON_ID);
        }
        entity.setProviderType(providerType);
        entity.setOpenaiBaseUrl(safe(req.getOpenaiBaseUrl()));
        entity.setOpenaiApiKey(safe(req.getOpenaiApiKey()));
        entity.setOpenaiModel(safe(req.getOpenaiModel()));
        entity.setCliCommand(safe(req.getCliCommand()));
        entity.setCliArgs(safe(req.getCliArgs()));
        entity.setCliWorkingDir(safe(req.getCliWorkingDir()));
        entity.setUpdatedAt(now);

        // 关键操作：按固定单例 ID 落库，保证本地配置只有一份可追踪数据。
        if (!exists) {
            aiConfigMapper.insert(entity);
        } else {
            aiConfigMapper.update(entity);
        }
        return toVO(entity);
    }

    private AiConfigVO defaultConfig() {
        AiConfigVO vo = new AiConfigVO();
        vo.setProviderType("OPENAI");
        vo.setOpenaiBaseUrl("https://api.openai.com/v1");
        vo.setOpenaiModel("gpt-4.1-mini");
        vo.setOpenaiApiKey("");
        vo.setCliCommand("");
        vo.setCliArgs("{prompt}");
        vo.setCliWorkingDir("");
        vo.setUpdatedAt(0L);
        return vo;
    }

    private AiConfigVO toVO(AiProviderConfigEntity entity) {
        AiConfigVO vo = new AiConfigVO();
        vo.setProviderType(entity.getProviderType());
        vo.setOpenaiBaseUrl(entity.getOpenaiBaseUrl());
        vo.setOpenaiApiKey(entity.getOpenaiApiKey());
        vo.setOpenaiModel(entity.getOpenaiModel());
        vo.setCliCommand(entity.getCliCommand());
        vo.setCliArgs(entity.getCliArgs());
        vo.setCliWorkingDir(entity.getCliWorkingDir());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
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
