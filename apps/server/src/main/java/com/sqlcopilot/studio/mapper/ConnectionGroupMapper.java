package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.ConnectionGroupEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ConnectionGroupMapper {

    @Select("""
        SELECT cg.*,
               (
                   SELECT COUNT(1)
                   FROM connection_info ci
                   WHERE ci.group_id = cg.id
               ) AS connection_count
        FROM connection_group cg
        ORDER BY CASE WHEN cg.name = '未分组' THEN 0 ELSE 1 END,
                 cg.sort_order ASC,
                 cg.id ASC
        """)
    List<ConnectionGroupEntity> findAll();

    @Select("SELECT * FROM connection_group WHERE id = #{id}")
    ConnectionGroupEntity findById(@Param("id") Long id);

    @Select("SELECT * FROM connection_group WHERE name = #{name}")
    ConnectionGroupEntity findByName(@Param("name") String name);

    @Insert("""
        INSERT INTO connection_group(name, sort_order, created_at, updated_at)
        VALUES (#{name}, #{sortOrder}, #{createdAt}, #{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConnectionGroupEntity entity);

    @Update("""
        UPDATE connection_group
        SET name = #{name}, updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int updateName(ConnectionGroupEntity entity);

    @Delete("DELETE FROM connection_group WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
