package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.MemoryEntryEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MemoryEntryMapper {

    @Insert("""
        INSERT INTO memory_entry(
            scope,
            connection_id,
            database_name,
            title,
            summary,
            structured_summary_json,
            source_type,
            source_session_id,
            source_history_ids_json,
            hit_count,
            last_used_at,
            created_at,
            updated_at
        ) VALUES (
            #{scope},
            #{connectionId},
            #{databaseName},
            #{title},
            #{summary},
            #{structuredSummaryJson},
            #{sourceType},
            #{sourceSessionId},
            #{sourceHistoryIdsJson},
            #{hitCount},
            #{lastUsedAt},
            #{createdAt},
            #{updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MemoryEntryEntity entity);

    @Update("""
        UPDATE memory_entry
        SET scope = #{scope},
            connection_id = #{connectionId},
            database_name = #{databaseName},
            title = #{title},
            summary = #{summary},
            structured_summary_json = #{structuredSummaryJson},
            source_type = #{sourceType},
            source_session_id = #{sourceSessionId},
            source_history_ids_json = #{sourceHistoryIdsJson},
            hit_count = #{hitCount},
            last_used_at = #{lastUsedAt},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int update(MemoryEntryEntity entity);

    @Select("SELECT * FROM memory_entry WHERE id = #{id} LIMIT 1")
    MemoryEntryEntity findById(@Param("id") Long id);

    @Select("""
        SELECT * FROM memory_entry
        WHERE source_type = 'AUTO_SESSION'
          AND source_session_id = #{sourceSessionId}
          AND scope = #{scope}
          AND connection_id = #{connectionId}
          AND database_name = #{databaseName}
        LIMIT 1
        """)
    MemoryEntryEntity findAutoSessionEntry(@Param("scope") String scope,
                                           @Param("connectionId") Long connectionId,
                                           @Param("databaseName") String databaseName,
                                           @Param("sourceSessionId") String sourceSessionId);

    @Delete("DELETE FROM memory_entry WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Delete("""
        DELETE FROM memory_entry
        WHERE connection_id = #{connectionId}
          AND database_name = #{databaseName}
        """)
    int deleteByDatabase(@Param("connectionId") Long connectionId,
                         @Param("databaseName") String databaseName);

    @Delete("DELETE FROM memory_entry WHERE connection_id = #{connectionId}")
    int deleteByConnection(@Param("connectionId") Long connectionId);

    @Select("""
        <script>
        SELECT COUNT(1)
        FROM memory_entry
        WHERE 1 = 1
        <if test='connectionId != null and connectionId &gt; 0'>
          AND connection_id = #{connectionId}
        </if>
        <if test='databaseName != null and databaseName != ""'>
          AND database_name = #{databaseName}
        </if>
        <if test='scope != null and scope != ""'>
          AND scope = #{scope}
        </if>
        <if test='keyword != null and keyword != ""'>
          AND (
            title LIKE '%' || #{keyword} || '%'
            OR summary LIKE '%' || #{keyword} || '%'
          )
        </if>
        </script>
        """)
    Long countPage(@Param("connectionId") Long connectionId,
                   @Param("databaseName") String databaseName,
                   @Param("scope") String scope,
                   @Param("keyword") String keyword);

    @Select("""
        <script>
        SELECT *
        FROM memory_entry
        WHERE 1 = 1
        <if test='connectionId != null and connectionId &gt; 0'>
          AND connection_id = #{connectionId}
        </if>
        <if test='databaseName != null and databaseName != ""'>
          AND database_name = #{databaseName}
        </if>
        <if test='scope != null and scope != ""'>
          AND scope = #{scope}
        </if>
        <if test='keyword != null and keyword != ""'>
          AND (
            title LIKE '%' || #{keyword} || '%'
            OR summary LIKE '%' || #{keyword} || '%'
          )
        </if>
        ORDER BY updated_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<MemoryEntryEntity> page(@Param("connectionId") Long connectionId,
                                 @Param("databaseName") String databaseName,
                                 @Param("scope") String scope,
                                 @Param("keyword") String keyword,
                                 @Param("limit") Integer limit,
                                 @Param("offset") Integer offset);

    @Update("""
        <script>
        UPDATE memory_entry
        SET hit_count = COALESCE(hit_count, 0) + 1,
            last_used_at = #{usedAt}
        WHERE id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>
          #{id}
        </foreach>
        </script>
        """)
    int markRetrieved(@Param("ids") List<Long> ids, @Param("usedAt") Long usedAt);
}
