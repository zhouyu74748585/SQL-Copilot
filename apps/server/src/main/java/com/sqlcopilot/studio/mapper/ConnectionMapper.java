package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.ConnectionEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ConnectionMapper {

    @Select("SELECT * FROM connection_info ORDER BY id DESC")
    List<ConnectionEntity> findAll();

    @Select("SELECT * FROM connection_info WHERE id = #{id}")
    ConnectionEntity findById(@Param("id") Long id);

    @Insert("""
        INSERT INTO connection_info (
            name, db_type, host, port, database_name, username, password, auth_type, env, read_only,
            ssh_enabled, ssh_host, ssh_port, ssh_user, last_test_status, last_test_message, created_at, updated_at
        ) VALUES (
            #{name}, #{dbType}, #{host}, #{port}, #{databaseName}, #{username}, #{password}, #{authType}, #{env}, #{readOnly},
            #{sshEnabled}, #{sshHost}, #{sshPort}, #{sshUser}, #{lastTestStatus}, #{lastTestMessage}, #{createdAt}, #{updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConnectionEntity entity);

    @Update("""
        UPDATE connection_info SET
            name = #{name}, db_type = #{dbType}, host = #{host}, port = #{port}, database_name = #{databaseName},
            username = #{username}, password = #{password}, auth_type = #{authType}, env = #{env}, read_only = #{readOnly},
            ssh_enabled = #{sshEnabled}, ssh_host = #{sshHost}, ssh_port = #{sshPort}, ssh_user = #{sshUser},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int update(ConnectionEntity entity);

    @Delete("DELETE FROM connection_info WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Update("""
        UPDATE connection_info
        SET last_test_status = #{status}, last_test_message = #{message}, updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int updateTestStatus(@Param("id") Long id,
                         @Param("status") String status,
                         @Param("message") String message,
                         @Param("updatedAt") Long updatedAt);
}
