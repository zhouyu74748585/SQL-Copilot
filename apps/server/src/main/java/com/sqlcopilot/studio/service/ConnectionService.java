package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.connection.ConnectionCreateReq;
import com.sqlcopilot.studio.dto.connection.ConnectionDatabasePreviewReq;
import com.sqlcopilot.studio.dto.connection.ConnectionDatabasePreviewVO;
import com.sqlcopilot.studio.dto.connection.ConnectionTestVO;
import com.sqlcopilot.studio.dto.connection.ConnectionUpdateReq;
import com.sqlcopilot.studio.dto.connection.ConnectionVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;

import java.util.List;

public interface ConnectionService {

    List<ConnectionVO> listConnections();

    ConnectionVO createConnection(ConnectionCreateReq req);

    ConnectionVO updateConnection(ConnectionUpdateReq req);

    ConnectionDatabasePreviewVO previewDatabases(ConnectionDatabasePreviewReq req);

    void removeConnection(Long id);

    ConnectionTestVO testConnection(Long connectionId);

    ConnectionEntity getConnectionEntity(Long id);

    java.sql.Connection openTargetConnection(Long connectionId) throws java.sql.SQLException;
}
