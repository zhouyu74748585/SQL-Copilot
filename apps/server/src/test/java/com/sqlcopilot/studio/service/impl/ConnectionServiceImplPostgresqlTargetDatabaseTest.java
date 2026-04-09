package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.mapper.ConnectionGroupMapper;
import com.sqlcopilot.studio.mapper.ConnectionMapper;
import com.sqlcopilot.studio.service.kv.KvRuntimeClientFactory;
import com.sqlcopilot.studio.support.JdbcDriverResolver;
import com.sqlcopilot.studio.support.driver.IsolatedJdbcConnectionManager;
import com.sqlcopilot.studio.support.ssh.SshTunnelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionServiceImplPostgresqlTargetDatabaseTest {

    @Mock
    private ConnectionMapper connectionMapper;

    @Mock
    private ConnectionGroupMapper connectionGroupMapper;

    @Mock
    private JdbcDriverResolver jdbcDriverResolver;

    @Mock
    private IsolatedJdbcConnectionManager isolatedJdbcConnectionManager;

    @Mock
    private SshTunnelManager sshTunnelManager;

    @Mock
    private KvRuntimeClientFactory kvRuntimeClientFactory;

    @Mock
    private Connection jdbcConnection;

    @Test
    void openTargetConnectionShouldReconnectToRequestedPostgresqlDatabase() throws Exception {
        ConnectionServiceImpl service = new ConnectionServiceImpl(
            connectionMapper,
            connectionGroupMapper,
            jdbcDriverResolver,
            isolatedJdbcConnectionManager,
            sshTunnelManager,
            kvRuntimeClientFactory,
            new ObjectMapper()
        );

        ConnectionEntity entity = new ConnectionEntity();
        entity.setId(1L);
        entity.setDbType("POSTGRESQL");
        entity.setHost("127.0.0.1");
        entity.setPort(5432);
        entity.setDatabaseName("postgres");
        entity.setUsername("demo");
        entity.setPassword("secret");
        entity.setSshEnabled(0);
        when(connectionMapper.findById(1L)).thenReturn(entity);
        when(isolatedJdbcConnectionManager.open(any(ConnectionEntity.class), anyString(), anyString(), anyString(), anyMap()))
            .thenReturn(jdbcConnection);

        Connection actual = service.openTargetConnection(1L, "analytics::public");

        assertThat(actual).isSameAs(jdbcConnection);
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(isolatedJdbcConnectionManager).open(any(ConnectionEntity.class), urlCaptor.capture(), eq("demo"), eq("secret"), anyMap());
        assertThat(urlCaptor.getValue()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/analytics");
    }
}
