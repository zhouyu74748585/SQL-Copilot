package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.RagEmbeddingConfigEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RagConfigMapper {

    @Select("""
        SELECT id,
               rag_embedding_provider_type,
               rag_embedding_model_dir,
               rag_embedding_online_base_url,
               rag_embedding_online_api_key,
               rag_embedding_online_model,
               rag_rerank_enabled,
               rag_rerank_provider_type,
               rag_rerank_model_dir,
               rag_rerank_online_base_url,
               rag_rerank_online_api_key,
               rag_rerank_online_model,
               updated_at
        FROM rag_embedding_config
        WHERE id = #{id}
        """)
    RagEmbeddingConfigEntity findById(@Param("id") Long id);

    @Insert("""
        INSERT INTO rag_embedding_config(
            id,
            rag_embedding_provider_type,
            rag_embedding_model_dir,
            rag_embedding_online_base_url,
            rag_embedding_online_api_key,
            rag_embedding_online_model,
            rag_rerank_enabled,
            rag_rerank_provider_type,
            rag_rerank_model_dir,
            rag_rerank_online_base_url,
            rag_rerank_online_api_key,
            rag_rerank_online_model,
            updated_at
        )
        VALUES(
            #{id},
            #{ragEmbeddingProviderType},
            #{ragEmbeddingModelDir},
            #{ragEmbeddingOnlineBaseUrl},
            #{ragEmbeddingOnlineApiKey},
            #{ragEmbeddingOnlineModel},
            #{ragRerankEnabled},
            #{ragRerankProviderType},
            #{ragRerankModelDir},
            #{ragRerankOnlineBaseUrl},
            #{ragRerankOnlineApiKey},
            #{ragRerankOnlineModel},
            #{updatedAt}
        )
        """)
    int insert(RagEmbeddingConfigEntity entity);

    @Update("""
        UPDATE rag_embedding_config
        SET rag_embedding_provider_type = #{ragEmbeddingProviderType},
            rag_embedding_model_dir = #{ragEmbeddingModelDir},
            rag_embedding_online_base_url = #{ragEmbeddingOnlineBaseUrl},
            rag_embedding_online_api_key = #{ragEmbeddingOnlineApiKey},
            rag_embedding_online_model = #{ragEmbeddingOnlineModel},
            rag_rerank_enabled = #{ragRerankEnabled},
            rag_rerank_provider_type = #{ragRerankProviderType},
            rag_rerank_model_dir = #{ragRerankModelDir},
            rag_rerank_online_base_url = #{ragRerankOnlineBaseUrl},
            rag_rerank_online_api_key = #{ragRerankOnlineApiKey},
            rag_rerank_online_model = #{ragRerankOnlineModel},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int update(RagEmbeddingConfigEntity entity);
}
