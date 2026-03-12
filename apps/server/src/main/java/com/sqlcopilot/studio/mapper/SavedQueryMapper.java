package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.SavedQueryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SavedQueryMapper {

    @Insert("""
        INSERT INTO saved_query(
            connection_id,
            database_name,
            title,
            sql_text,
            created_at,
            updated_at
        ) VALUES (
            #{connectionId},
            #{databaseName},
            #{title},
            #{sqlText},
            #{createdAt},
            #{updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SavedQueryEntity entity);

    @Select("""
        SELECT *
        FROM saved_query
        WHERE connection_id = #{connectionId}
          AND database_name = #{databaseName}
          AND title = #{title}
        LIMIT 1
        """)
    SavedQueryEntity findByUniqueKey(@Param("connectionId") Long connectionId,
                                     @Param("databaseName") String databaseName,
                                     @Param("title") String title);

    @Select("""
        SELECT *
        FROM saved_query
        WHERE id = #{id}
        LIMIT 1
        """)
    SavedQueryEntity findById(@Param("id") Long id);

    @Select("""
        SELECT *
        FROM saved_query
        WHERE connection_id = #{connectionId}
          AND database_name = #{databaseName}
        ORDER BY updated_at DESC, id DESC
        """)
    List<SavedQueryEntity> listByDatabase(@Param("connectionId") Long connectionId,
                                          @Param("databaseName") String databaseName);

    @Update("""
        UPDATE saved_query
        SET database_name = #{databaseName},
            title = #{title},
            sql_text = #{sqlText},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int updateById(SavedQueryEntity entity);

    @Delete("""
        DELETE FROM saved_query
        WHERE connection_id = #{connectionId}
          AND id = #{id}
        """)
    int deleteById(@Param("connectionId") Long connectionId,
                   @Param("id") Long id);
}
