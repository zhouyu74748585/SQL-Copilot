package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.AiProviderConfigEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiConfigMapper {

    @Select("""
        SELECT id, provider_type, openai_base_url, openai_api_key, openai_model,
               cli_command, cli_working_dir,
               model_options_json,
               conversation_memory_enabled,
               conversation_memory_window_size,
               updated_at
        FROM ai_provider_config
        WHERE id = #{id}
        """)
    AiProviderConfigEntity findById(@Param("id") Long id);

    @Insert("""
        INSERT INTO ai_provider_config(
            id, provider_type, openai_base_url, openai_api_key, openai_model,
            cli_command, cli_working_dir,
            model_options_json,
            conversation_memory_enabled,
            conversation_memory_window_size,
            updated_at
        )
        VALUES(
            #{id}, #{providerType}, #{openaiBaseUrl}, #{openaiApiKey}, #{openaiModel},
            #{cliCommand}, #{cliWorkingDir},
            #{modelOptionsJson},
            #{conversationMemoryEnabled},
            #{conversationMemoryWindowSize},
            #{updatedAt}
        )
        """)
    int insert(AiProviderConfigEntity entity);

    @Update("""
        UPDATE ai_provider_config
        SET provider_type = #{providerType},
            openai_base_url = #{openaiBaseUrl},
            openai_api_key = #{openaiApiKey},
            openai_model = #{openaiModel},
            cli_command = #{cliCommand},
            cli_working_dir = #{cliWorkingDir},
            model_options_json = #{modelOptionsJson},
            conversation_memory_enabled = #{conversationMemoryEnabled},
            conversation_memory_window_size = #{conversationMemoryWindowSize},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int update(AiProviderConfigEntity entity);
}
