package com.sqlcopilot.studio.service;

public interface MetadataChangeSyncService {

    void onConnectionRemoved(Long connectionId);

    void onDatabaseCreated(Long connectionId, String databaseName);

    void onDatabaseRenamed(Long connectionId, String sourceDatabaseName, String targetDatabaseName);

    void onDatabaseDropped(Long connectionId, String databaseName);

    void onTableCreatedOrAltered(Long connectionId, String databaseName, String tableName);

    void onTableRenamed(Long connectionId, String databaseName, String sourceTableName, String targetTableName);

    void onTableDropped(Long connectionId, String databaseName, String tableName);

    void onDatabaseMetadataChanged(Long connectionId, String databaseName);
}
