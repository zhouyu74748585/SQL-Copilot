package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.RagVectorizeStatusEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RagVectorizeStatusMapper {

    @Select("""
        SELECT connection_id, database_name, status, message, updated_at,
               last_full_vectorize_duration_ms, last_full_vectorize_provider
        FROM rag_vectorize_status
        WHERE connection_id = #{connectionId}
        ORDER BY database_name COLLATE NOCASE
        """)
    List<RagVectorizeStatusEntity> findByConnectionId(@Param("connectionId") Long connectionId);

    @Select("""
        SELECT connection_id, database_name, status, message, updated_at,
               last_full_vectorize_duration_ms, last_full_vectorize_provider
        FROM rag_vectorize_status
        WHERE status IN ('PENDING', 'RUNNING')
        """)
    List<RagVectorizeStatusEntity> findInProgress();

    @Select("""
        SELECT connection_id, database_name, status, message, updated_at,
               last_full_vectorize_duration_ms, last_full_vectorize_provider
        FROM rag_vectorize_status
        WHERE connection_id = #{connectionId}
          AND database_name = #{databaseName}
        LIMIT 1
        """)
    RagVectorizeStatusEntity findOne(@Param("connectionId") Long connectionId, @Param("databaseName") String databaseName);

    @Insert("""
        INSERT INTO rag_vectorize_status(
            connection_id, database_name, status, message, updated_at,
            last_full_vectorize_duration_ms, last_full_vectorize_provider
        )
        VALUES(
            #{connectionId}, #{databaseName}, #{status}, #{message}, #{updatedAt},
            #{lastFullVectorizeDurationMs}, #{lastFullVectorizeProvider}
        )
        ON CONFLICT(connection_id, database_name) DO UPDATE SET
            status = excluded.status,
            message = excluded.message,
            updated_at = excluded.updated_at,
            last_full_vectorize_duration_ms =
                COALESCE(excluded.last_full_vectorize_duration_ms, rag_vectorize_status.last_full_vectorize_duration_ms),
            last_full_vectorize_provider =
                COALESCE(excluded.last_full_vectorize_provider, rag_vectorize_status.last_full_vectorize_provider)
        """)
    int upsert(RagVectorizeStatusEntity entity);
}
