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
               rag_embedding_model_file_name,
               rag_embedding_model_data_file_name,
               rag_embedding_tokenizer_file_name,
               rag_embedding_tokenizer_config_file_name,
               rag_embedding_config_file_name,
               rag_embedding_special_tokens_file_name,
               rag_embedding_sentencepiece_file_name,
               rag_embedding_model_path,
               rag_embedding_model_data_path,
               updated_at
        FROM rag_embedding_config
        WHERE id = #{id}
        """)
    RagEmbeddingConfigEntity findById(@Param("id") Long id);

    @Insert("""
        INSERT INTO rag_embedding_config(
            id,
            rag_embedding_model_dir,
            rag_embedding_model_file_name,
            rag_embedding_model_data_file_name,
            rag_embedding_tokenizer_file_name,
            rag_embedding_tokenizer_config_file_name,
            rag_embedding_config_file_name,
            rag_embedding_special_tokens_file_name,
            rag_embedding_sentencepiece_file_name,
            rag_embedding_model_path,
            rag_embedding_model_data_path,
            updated_at
        )
        VALUES(
            #{id},
            #{ragEmbeddingModelDir},
            #{ragEmbeddingModelFileName},
            #{ragEmbeddingModelDataFileName},
            #{ragEmbeddingTokenizerFileName},
            #{ragEmbeddingTokenizerConfigFileName},
            #{ragEmbeddingConfigFileName},
            #{ragEmbeddingSpecialTokensFileName},
            #{ragEmbeddingSentencepieceFileName},
            #{ragEmbeddingModelPath},
            #{ragEmbeddingModelDataPath},
            #{updatedAt}
        )
        """)
    int insert(RagEmbeddingConfigEntity entity);

    @Update("""
        UPDATE rag_embedding_config
        SET rag_embedding_model_dir = #{ragEmbeddingModelDir},
            rag_embedding_model_file_name = #{ragEmbeddingModelFileName},
            rag_embedding_model_data_file_name = #{ragEmbeddingModelDataFileName},
            rag_embedding_tokenizer_file_name = #{ragEmbeddingTokenizerFileName},
            rag_embedding_tokenizer_config_file_name = #{ragEmbeddingTokenizerConfigFileName},
            rag_embedding_config_file_name = #{ragEmbeddingConfigFileName},
            rag_embedding_special_tokens_file_name = #{ragEmbeddingSpecialTokensFileName},
            rag_embedding_sentencepiece_file_name = #{ragEmbeddingSentencepieceFileName},
            rag_embedding_model_path = #{ragEmbeddingModelPath},
            rag_embedding_model_data_path = #{ragEmbeddingModelDataPath},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int update(RagEmbeddingConfigEntity entity);
}
