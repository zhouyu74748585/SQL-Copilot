package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.dto.editor.QueryHistorySessionVO;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import org.apache.ibatis.annotations.*;

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
            structured_context_json,
            trace_json,
            token_estimate,
            turn_content_tokens,
            request_prompt_tokens,
            request_completion_tokens,
            request_total_tokens,
            token_estimate_source,
            token_estimate_version,
            token_estimate_scope,
            prompt_budget_json,
            memory_enabled,
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
            #{structuredContextJson},
            #{traceJson},
            #{tokenEstimate},
            #{turnContentTokens},
            #{requestPromptTokens},
            #{requestCompletionTokens},
            #{requestTotalTokens},
            #{tokenEstimateSource},
            #{tokenEstimateVersion},
            #{tokenEstimateScope},
            #{promptBudgetJson},
            #{memoryEnabled},
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
                COUNT(1) AS messageCount,
                SUM(COALESCE(q.request_total_tokens, q.token_estimate, 0)) AS totalTokens
            FROM query_history q
            WHERE q.connection_id = #{connectionId}
              AND q.session_id IS NOT NULL
              AND TRIM(q.session_id) <> ''
              AND COALESCE(TRIM(q.history_type), 'CHAT') = 'CHAT'
            GROUP BY q.connection_id, q.session_id
        )
        SELECT connectionId, sessionId, title, createdAt, updatedAt, messageCount, totalTokens
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

    @Select("""
        <script>
        SELECT COUNT(1)
        FROM query_history
        WHERE COALESCE(TRIM(history_type), 'CHAT') = 'EXECUTE'
          AND COALESCE(success_flag, 0) = 1
          AND COALESCE(memory_enabled, 0) = 1
        <if test='connectionId != null and connectionId &gt; 0'>
          AND connection_id = #{connectionId}
        </if>
        <if test='databaseName != null and databaseName != ""'>
          AND database_name = #{databaseName}
        </if>
        <if test='keyword != null and keyword != ""'>
          AND (
            COALESCE(prompt_text, '') LIKE '%' || #{keyword} || '%'
            OR COALESCE(sql_text, '') LIKE '%' || #{keyword} || '%'
            OR COALESCE(assistant_content, '') LIKE '%' || #{keyword} || '%'
          )
        </if>
        </script>
        """)
    Long countSuccessfulExecuteHistory(@Param("connectionId") Long connectionId,
                                       @Param("databaseName") String databaseName,
                                       @Param("keyword") String keyword);

    @Select("""
        <script>
        SELECT *
        FROM query_history
        WHERE COALESCE(TRIM(history_type), 'CHAT') = 'EXECUTE'
          AND COALESCE(success_flag, 0) = 1
          AND COALESCE(memory_enabled, 0) = 1
        <if test='connectionId != null and connectionId &gt; 0'>
          AND connection_id = #{connectionId}
        </if>
        <if test='databaseName != null and databaseName != ""'>
          AND database_name = #{databaseName}
        </if>
        <if test='keyword != null and keyword != ""'>
          AND (
            COALESCE(prompt_text, '') LIKE '%' || #{keyword} || '%'
            OR COALESCE(sql_text, '') LIKE '%' || #{keyword} || '%'
            OR COALESCE(assistant_content, '') LIKE '%' || #{keyword} || '%'
          )
        </if>
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<QueryHistoryEntity> pageSuccessfulExecuteHistory(@Param("connectionId") Long connectionId,
                                                          @Param("databaseName") String databaseName,
                                                          @Param("keyword") String keyword,
                                                          @Param("limit") Integer limit,
                                                          @Param("offset") Integer offset);

    @Select("""
        <script>
        SELECT *
        FROM query_history
        WHERE id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>
          #{id}
        </foreach>
        </script>
        """)
    List<QueryHistoryEntity> listByIds(@Param("ids") List<Long> ids);

    @Delete("""
        DELETE FROM query_history
        WHERE connection_id = #{connectionId}
          AND session_id = #{sessionId}
        """)
    int deleteBySession(@Param("connectionId") Long connectionId,
                        @Param("sessionId") String sessionId);
}
