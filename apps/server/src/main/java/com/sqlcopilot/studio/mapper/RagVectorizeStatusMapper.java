package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.RagVectorizeStatusEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RagVectorizeStatusMapper {

    @Select("""
        SELECT connection_id, database_name, status, message, updated_at
        FROM rag_vectorize_status
        WHERE connection_id = #{connectionId}
        ORDER BY database_name COLLATE NOCASE
        """)
    List<RagVectorizeStatusEntity> findByConnectionId(@Param("connectionId") Long connectionId);

    @Select("""
        SELECT connection_id, database_name, status, message, updated_at
        FROM rag_vectorize_status
        WHERE status IN ('PENDING', 'RUNNING')
        """)
    List<RagVectorizeStatusEntity> findInProgress();

    @Select("""
        SELECT connection_id, database_name, status, message, updated_at
        FROM rag_vectorize_status
        WHERE connection_id = #{connectionId}
          AND database_name = #{databaseName}
        LIMIT 1
        """)
    RagVectorizeStatusEntity findOne(@Param("connectionId") Long connectionId, @Param("databaseName") String databaseName);

    @Insert("""
        INSERT INTO rag_vectorize_status(
            connection_id, database_name, status, message, updated_at
        )
        VALUES(
            #{connectionId}, #{databaseName}, #{status}, #{message}, #{updatedAt}
        )
        ON CONFLICT(connection_id, database_name) DO UPDATE SET
            status = excluded.status,
            message = excluded.message,
            updated_at = excluded.updated_at
        """)
    int upsert(RagVectorizeStatusEntity entity);
}
