package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.KnowledgeExampleSqlEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KnowledgeExampleSqlMapper {

    @Insert("""
        INSERT INTO knowledge_example_sql(
            scope,
            connection_id,
            database_name,
            sql_text,
            description,
            term_ids_json,
            created_at,
            updated_at
        ) VALUES (
            #{scope},
            #{connectionId},
            #{databaseName},
            #{sqlText},
            #{description},
            #{termIdsJson},
            #{createdAt},
            #{updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeExampleSqlEntity entity);

    @Update("""
        UPDATE knowledge_example_sql
        SET scope = #{scope},
            connection_id = #{connectionId},
            database_name = #{databaseName},
            sql_text = #{sqlText},
            description = #{description},
            term_ids_json = #{termIdsJson},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int update(KnowledgeExampleSqlEntity entity);

    @Select("SELECT * FROM knowledge_example_sql WHERE id = #{id} LIMIT 1")
    KnowledgeExampleSqlEntity findById(@Param("id") Long id);

    @Delete("DELETE FROM knowledge_example_sql WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Select("""
        <script>
        SELECT *
        FROM knowledge_example_sql
        WHERE scope = 'GLOBAL'
        <if test='connectionId != null'>
          OR (scope = 'CONNECTION' AND connection_id = #{connectionId})
        </if>
        <if test='connectionId != null and databaseName != null and databaseName != ""'>
          OR (scope = 'DATABASE' AND connection_id = #{connectionId} AND database_name = #{databaseName})
        </if>
        ORDER BY
          CASE scope
            WHEN 'DATABASE' THEN 1
            WHEN 'CONNECTION' THEN 2
            ELSE 3
          END,
          updated_at DESC,
          id DESC
        </script>
        """)
    List<KnowledgeExampleSqlEntity> listApplicable(@Param("connectionId") Long connectionId,
                                                   @Param("databaseName") String databaseName);

    @Select("SELECT * FROM knowledge_example_sql ORDER BY updated_at DESC, id DESC")
    List<KnowledgeExampleSqlEntity> listAll();
}
