package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.connection.*;
import com.sqlcopilot.studio.entity.ConnectionEntity;

import java.util.List;

public interface ConnectionService {

    List<ConnectionDbTypeVO> listSupportedDbTypes();

    List<ConnectionGroupVO> listConnectionGroups();

    List<ConnectionVO> listConnections();

    ConnectionVO createConnection(ConnectionCreateReq req);

    ConnectionVO updateConnection(ConnectionUpdateReq req);

    ConnectionDatabasePreviewVO previewDatabases(ConnectionDatabasePreviewReq req);

    ConnectionGroupVO createConnectionGroup(ConnectionGroupCreateReq req);

    ConnectionGroupVO renameConnectionGroup(ConnectionGroupRenameReq req);

    void removeConnectionGroup(Long groupId);

    void moveConnectionToGroup(Long connectionId, Long targetGroupId);

    void removeConnection(Long id);

    void disconnectConnection(Long id);

    ConnectionTestVO testConnection(Long connectionId);

    ConnectionEntity getConnectionEntity(Long id);

    java.sql.Connection openTargetConnection(Long connectionId) throws java.sql.SQLException;

    default java.sql.Connection openTargetConnection(Long connectionId, String databaseName) throws java.sql.SQLException {
        return openTargetConnection(connectionId);
    }
}
