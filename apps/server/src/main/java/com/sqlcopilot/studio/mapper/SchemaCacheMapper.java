package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.SchemaColumnCacheEntity;
import com.sqlcopilot.studio.entity.SchemaTableCacheEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SchemaCacheMapper {

    @Delete("DELETE FROM schema_table_cache WHERE connection_id = #{connectionId} AND database_name = #{databaseName}")
    int deleteTables(@Param("connectionId") Long connectionId, @Param("databaseName") String databaseName);

    @Delete("DELETE FROM schema_column_cache WHERE connection_id = #{connectionId} AND database_name = #{databaseName}")
    int deleteColumns(@Param("connectionId") Long connectionId, @Param("databaseName") String databaseName);

    @Insert("""
        INSERT INTO schema_table_cache(connection_id, database_name, table_name, table_comment, row_estimate, table_size_bytes, updated_at)
        VALUES(#{connectionId}, #{databaseName}, #{tableName}, #{tableComment}, #{rowEstimate}, #{tableSizeBytes}, #{updatedAt})
        """)
    int insertTable(SchemaTableCacheEntity entity);

    @Insert("""
        INSERT INTO schema_column_cache(
            connection_id, database_name, table_name, column_name, data_type, column_size, decimal_digits,
            column_default, auto_increment_flag, nullable_flag, column_comment,
            indexed_flag, primary_key_flag, updated_at
        ) VALUES(
            #{connectionId}, #{databaseName}, #{tableName}, #{columnName}, #{dataType}, #{columnSize}, #{decimalDigits},
            #{columnDefault}, #{autoIncrementFlag}, #{nullableFlag}, #{columnComment},
            #{indexedFlag}, #{primaryKeyFlag}, #{updatedAt}
        )
        """)
    int insertColumn(SchemaColumnCacheEntity entity);

    @Select("""
        SELECT * FROM schema_table_cache
        WHERE connection_id = #{connectionId} AND database_name = #{databaseName}
        ORDER BY table_name
        """)
    List<SchemaTableCacheEntity> findTables(@Param("connectionId") Long connectionId, @Param("databaseName") String databaseName);

    @Select("""
        SELECT * FROM schema_column_cache
        WHERE connection_id = #{connectionId} AND database_name = #{databaseName} AND table_name = #{tableName}
        ORDER BY id
        """)
    List<SchemaColumnCacheEntity> findColumnsByTable(@Param("connectionId") Long connectionId,
                                                      @Param("databaseName") String databaseName,
                                                      @Param("tableName") String tableName);

    @Select("""
        SELECT * FROM schema_column_cache
        WHERE connection_id = #{connectionId} AND database_name = #{databaseName}
        ORDER BY table_name, id
        """)
    List<SchemaColumnCacheEntity> findColumns(@Param("connectionId") Long connectionId, @Param("databaseName") String databaseName);

    @Select("""
        SELECT COUNT(1) FROM schema_table_cache
        WHERE connection_id = #{connectionId} AND database_name = #{databaseName}
        """)
    Integer countTables(@Param("connectionId") Long connectionId, @Param("databaseName") String databaseName);

    @Select("""
        SELECT COUNT(1) FROM schema_column_cache
        WHERE connection_id = #{connectionId} AND database_name = #{databaseName}
        """)
    Integer countColumns(@Param("connectionId") Long connectionId, @Param("databaseName") String databaseName);
}
