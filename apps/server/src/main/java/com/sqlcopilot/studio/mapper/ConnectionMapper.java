package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.ConnectionEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ConnectionMapper {

    @Select("""
        SELECT ci.*, cg.name AS group_name
        FROM connection_info ci
        LEFT JOIN connection_group cg
          ON ci.group_id = cg.id
        ORDER BY ci.id DESC
        """)
    List<ConnectionEntity> findAll();

    @Select("""
        SELECT ci.*, cg.name AS group_name
        FROM connection_info ci
        LEFT JOIN connection_group cg
          ON ci.group_id = cg.id
        WHERE ci.id = #{id}
        """)
    ConnectionEntity findById(@Param("id") Long id);

    @Insert("""
        INSERT INTO connection_info (
            name, db_type, group_id, host, port, database_name, custom_params, username, password, auth_type, env, read_only,
            ssh_enabled, ssh_host, ssh_port, ssh_user, ssh_auth_type, ssh_password, ssh_private_key_path,
            ssh_private_key_text, ssh_private_key_passphrase, selected_databases_json,
            last_test_status, last_test_message, created_at, updated_at
        ) VALUES (
            #{name}, #{dbType}, #{groupId}, #{host}, #{port}, #{databaseName}, #{customParams}, #{username}, #{password}, #{authType}, #{env}, #{readOnly},
            #{sshEnabled}, #{sshHost}, #{sshPort}, #{sshUser}, #{sshAuthType}, #{sshPassword}, #{sshPrivateKeyPath},
            #{sshPrivateKeyText}, #{sshPrivateKeyPassphrase}, #{selectedDatabasesJson},
            #{lastTestStatus}, #{lastTestMessage}, #{createdAt}, #{updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConnectionEntity entity);

    @Update("""
        UPDATE connection_info SET
            name = #{name}, db_type = #{dbType}, group_id = #{groupId}, host = #{host}, port = #{port}, database_name = #{databaseName},
            custom_params = #{customParams},
            username = #{username}, password = #{password}, auth_type = #{authType}, env = #{env}, read_only = #{readOnly},
            ssh_enabled = #{sshEnabled}, ssh_host = #{sshHost}, ssh_port = #{sshPort}, ssh_user = #{sshUser},
            ssh_auth_type = #{sshAuthType}, ssh_password = #{sshPassword}, ssh_private_key_path = #{sshPrivateKeyPath},
            ssh_private_key_text = #{sshPrivateKeyText}, ssh_private_key_passphrase = #{sshPrivateKeyPassphrase},
            selected_databases_json = #{selectedDatabasesJson},
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

    @Update("""
        UPDATE connection_info
        SET group_id = #{groupId}, updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int updateGroupId(@Param("id") Long id,
                      @Param("groupId") Long groupId,
                      @Param("updatedAt") Long updatedAt);

    @Update("""
        UPDATE connection_info
        SET group_id = #{targetGroupId}, updated_at = #{updatedAt}
        WHERE group_id = #{sourceGroupId}
        """)
    int moveConnectionsToGroup(@Param("sourceGroupId") Long sourceGroupId,
                               @Param("targetGroupId") Long targetGroupId,
                               @Param("updatedAt") Long updatedAt);
}
