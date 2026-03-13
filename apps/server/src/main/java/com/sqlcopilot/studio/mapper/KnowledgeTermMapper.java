package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.KnowledgeTermEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KnowledgeTermMapper {

    @Insert("""
        INSERT INTO knowledge_term(
            scope,
            connection_id,
            database_name,
            term,
            description,
            aliases_json,
            metric_expression,
            related_tables_json,
            related_columns_json,
            term_type,
            created_at,
            updated_at
        ) VALUES (
            #{scope},
            #{connectionId},
            #{databaseName},
            #{term},
            #{description},
            #{aliasesJson},
            #{metricExpression},
            #{relatedTablesJson},
            #{relatedColumnsJson},
            #{termType},
            #{createdAt},
            #{updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeTermEntity entity);

    @Update("""
        UPDATE knowledge_term
        SET scope = #{scope},
            connection_id = #{connectionId},
            database_name = #{databaseName},
            term = #{term},
            description = #{description},
            aliases_json = #{aliasesJson},
            metric_expression = #{metricExpression},
            related_tables_json = #{relatedTablesJson},
            related_columns_json = #{relatedColumnsJson},
            term_type = #{termType},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int update(KnowledgeTermEntity entity);

    @Select("SELECT * FROM knowledge_term WHERE id = #{id} LIMIT 1")
    KnowledgeTermEntity findById(@Param("id") Long id);

    @Delete("DELETE FROM knowledge_term WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Select("""
        <script>
        SELECT *
        FROM knowledge_term
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
    List<KnowledgeTermEntity> listApplicable(@Param("connectionId") Long connectionId,
                                             @Param("databaseName") String databaseName);

    @Select("SELECT * FROM knowledge_term ORDER BY updated_at DESC, id DESC")
    List<KnowledgeTermEntity> listAll();
}
