package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.ErGraphSnapshotEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ErGraphSnapshotMapper {

    @Insert("""
        INSERT INTO er_graph_snapshot(
            connection_id,
            database_name,
            snapshot_name,
            selected_tables_json,
            model_name,
            layout_mode,
            ai_confidence_threshold,
            include_ai_inference,
            graph_json,
            created_at,
            updated_at
        )
        VALUES(
            #{connectionId},
            #{databaseName},
            #{snapshotName},
            #{selectedTablesJson},
            #{modelName},
            #{layoutMode},
            #{aiConfidenceThreshold},
            #{includeAiInference},
            #{graphJson},
            #{createdAt},
            #{updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ErGraphSnapshotEntity entity);

    @Select("""
        SELECT *
        FROM er_graph_snapshot
        WHERE connection_id = #{connectionId}
          AND (#{keyword} IS NULL OR #{keyword} = ''
               OR snapshot_name LIKE '%' || #{keyword} || '%'
               OR database_name LIKE '%' || #{keyword} || '%')
        ORDER BY updated_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<ErGraphSnapshotEntity> pageByConnection(@Param("connectionId") Long connectionId,
                                                 @Param("keyword") String keyword,
                                                 @Param("limit") Integer limit,
                                                 @Param("offset") Integer offset);

    @Select("""
        SELECT COUNT(1)
        FROM er_graph_snapshot
        WHERE connection_id = #{connectionId}
          AND (#{keyword} IS NULL OR #{keyword} = ''
               OR snapshot_name LIKE '%' || #{keyword} || '%'
               OR database_name LIKE '%' || #{keyword} || '%')
        """)
    Long countByConnection(@Param("connectionId") Long connectionId,
                           @Param("keyword") String keyword);

    @Select("""
        SELECT *
        FROM er_graph_snapshot
        WHERE id = #{id}
        LIMIT 1
        """)
    ErGraphSnapshotEntity getById(@Param("id") Long id);

    @Update("""
        UPDATE er_graph_snapshot
        SET snapshot_name = #{snapshotName},
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND connection_id = #{connectionId}
        """)
    int updateSnapshotName(@Param("connectionId") Long connectionId,
                           @Param("id") Long id,
                           @Param("snapshotName") String snapshotName,
                           @Param("updatedAt") Long updatedAt);

    @Update("""
        UPDATE er_graph_snapshot
        SET database_name = #{databaseName},
            snapshot_name = #{snapshotName},
            selected_tables_json = #{selectedTablesJson},
            model_name = #{modelName},
            layout_mode = #{layoutMode},
            ai_confidence_threshold = #{aiConfidenceThreshold},
            include_ai_inference = #{includeAiInference},
            graph_json = #{graphJson},
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND connection_id = #{connectionId}
        """)
    int updateSnapshotContent(ErGraphSnapshotEntity entity);

    @Delete("""
        DELETE FROM er_graph_snapshot
        WHERE id = #{id}
          AND connection_id = #{connectionId}
        """)
    int deleteById(@Param("connectionId") Long connectionId,
                   @Param("id") Long id);
}
