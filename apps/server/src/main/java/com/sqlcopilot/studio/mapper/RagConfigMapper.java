package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.RagEmbeddingConfigEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RagConfigMapper {

    @Select("""
        SELECT id,
               rag_embedding_model_dir,
               rag_rerank_enabled,
               rag_rerank_model_dir,
               updated_at
        FROM rag_embedding_config
        WHERE id = #{id}
        """)
    RagEmbeddingConfigEntity findById(@Param("id") Long id);

    @Insert("""
        INSERT INTO rag_embedding_config(
            id,
            rag_embedding_model_dir,
            rag_rerank_enabled,
            rag_rerank_model_dir,
            updated_at
        )
        VALUES(
            #{id},
            #{ragEmbeddingModelDir},
            #{ragRerankEnabled},
            #{ragRerankModelDir},
            #{updatedAt}
        )
        """)
    int insert(RagEmbeddingConfigEntity entity);

    @Update("""
        UPDATE rag_embedding_config
        SET rag_embedding_model_dir = #{ragEmbeddingModelDir},
            rag_rerank_enabled = #{ragRerankEnabled},
            rag_rerank_model_dir = #{ragRerankModelDir},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int update(RagEmbeddingConfigEntity entity);
}
