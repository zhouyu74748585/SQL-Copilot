package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface QueryHistoryMapper {

    @Insert("""
        INSERT INTO query_history(connection_id, session_id, prompt_text, sql_text, execution_ms, success_flag, created_at)
        VALUES(#{connectionId}, #{sessionId}, #{promptText}, #{sqlText}, #{executionMs}, #{successFlag}, #{createdAt})
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
}
