package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.dto.editor.QueryHistorySessionVO;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface QueryHistoryMapper {

    @Insert("""
        INSERT INTO query_history(
            connection_id,
            session_id,
            prompt_text,
            sql_text,
            history_type,
            action_type,
            assistant_content,
            database_name,
            chart_config_json,
            chart_image_cache_key,
            execution_ms,
            success_flag,
            created_at
        )
        VALUES(
            #{connectionId},
            #{sessionId},
            #{promptText},
            #{sqlText},
            #{historyType},
            #{actionType},
            #{assistantContent},
            #{databaseName},
            #{chartConfigJson},
            #{chartImageCacheKey},
            #{executionMs},
            #{successFlag},
            #{createdAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(QueryHistoryEntity entity);

    @Select("""
        SELECT * FROM query_history
        WHERE connection_id = #{connectionId}
        ORDER BY id DESC
        LIMIT #{limit}
        """)
    List<QueryHistoryEntity> listByConnection(@Param("connectionId") Long connectionId, @Param("limit") Integer limit);

    @Select("""
        WITH session_summary AS (
            SELECT
                q.connection_id AS connectionId,
                q.session_id AS sessionId,
                COALESCE((
                    SELECT TRIM(q1.prompt_text)
                    FROM query_history q1
                    WHERE q1.connection_id = q.connection_id
                      AND q1.session_id = q.session_id
                      AND COALESCE(TRIM(q1.history_type), 'CHAT') = 'CHAT'
                      AND q1.prompt_text IS NOT NULL
                      AND TRIM(q1.prompt_text) <> ''
                    ORDER BY q1.id ASC
                    LIMIT 1
                ), '未命名会话') AS title,
                MIN(q.created_at) AS createdAt,
                MAX(q.created_at) AS updatedAt,
                COUNT(1) AS messageCount
            FROM query_history q
            WHERE q.connection_id = #{connectionId}
              AND q.session_id IS NOT NULL
              AND TRIM(q.session_id) <> ''
              AND COALESCE(TRIM(q.history_type), 'CHAT') = 'CHAT'
            GROUP BY q.connection_id, q.session_id
        )
        SELECT connectionId, sessionId, title, createdAt, updatedAt, messageCount
        FROM session_summary
        WHERE (#{keyword} IS NULL OR #{keyword} = '' OR title LIKE '%' || #{keyword} || '%')
        ORDER BY updatedAt DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<QueryHistorySessionVO> pageSessions(@Param("connectionId") Long connectionId,
                                             @Param("keyword") String keyword,
                                             @Param("limit") Integer limit,
                                             @Param("offset") Integer offset);

    @Select("""
        WITH session_summary AS (
            SELECT
                q.connection_id AS connectionId,
                q.session_id AS sessionId,
                COALESCE((
                    SELECT TRIM(q1.prompt_text)
                    FROM query_history q1
                    WHERE q1.connection_id = q.connection_id
                      AND q1.session_id = q.session_id
                      AND COALESCE(TRIM(q1.history_type), 'CHAT') = 'CHAT'
                      AND q1.prompt_text IS NOT NULL
                      AND TRIM(q1.prompt_text) <> ''
                    ORDER BY q1.id ASC
                    LIMIT 1
                ), '未命名会话') AS title
            FROM query_history q
            WHERE q.connection_id = #{connectionId}
              AND q.session_id IS NOT NULL
              AND TRIM(q.session_id) <> ''
              AND COALESCE(TRIM(q.history_type), 'CHAT') = 'CHAT'
            GROUP BY q.connection_id, q.session_id
        )
        SELECT COUNT(1)
        FROM session_summary
        WHERE (#{keyword} IS NULL OR #{keyword} = '' OR title LIKE '%' || #{keyword} || '%')
        """)
    Long countSessions(@Param("connectionId") Long connectionId, @Param("keyword") String keyword);

    @Select("""
        SELECT * FROM query_history
        WHERE connection_id = #{connectionId}
          AND session_id = #{sessionId}
          AND COALESCE(TRIM(history_type), 'CHAT') = 'CHAT'
        ORDER BY id ASC
        LIMIT #{limit}
        """)
    List<QueryHistoryEntity> listBySession(@Param("connectionId") Long connectionId,
                                           @Param("sessionId") String sessionId,
                                           @Param("limit") Integer limit);

    @Delete("""
        DELETE FROM query_history
        WHERE connection_id = #{connectionId}
          AND session_id = #{sessionId}
        """)
    int deleteBySession(@Param("connectionId") Long connectionId,
                        @Param("sessionId") String sessionId);
}
