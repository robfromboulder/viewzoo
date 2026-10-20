// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.robfromboulder.viewzoo.config.ViewZooJdbcConfig;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.ViewNotFoundException;
import io.trino.spi.type.TypeId;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static java.util.stream.Collectors.toMap;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TestViewZooJdbcClient {

    private static final SchemaTableName SOURCE = new SchemaTableName("s", "source");
    private static final SchemaTableName TARGET = new SchemaTableName("t", "target");

    private PostgreSQLContainer postgres;
    private ViewZooJdbcConfig config;
    private final List<Object> resources = new ArrayList<>();
    private ViewZooJdbcClient client;

    @BeforeClass
    public void startPostgres() {
        if (!DockerClientFactory.instance().isDockerAvailable()) throw new SkipException("Docker is not available");
        postgres = new PostgreSQLContainer("postgres:16");
        postgres.start();
        config = new ViewZooJdbcConfig().setJdbcUrl(postgres.getJdbcUrl()).setJdbcUser(postgres.getUsername()).setJdbcPassword(postgres.getPassword());
    }

    @AfterClass(alwaysRun = true)
    public void stopPostgres() {
        if (postgres != null) postgres.stop();
        postgres = null;
    }

    @BeforeMethod
    public void setUp() throws SQLException {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS viewzoo");
        }
        resources.clear();
        client = new TrackingJdbcClient();
    }

    @Test
    public void testCreatesTable() {
        assertEquals(client.getViews(), Map.of());
    }

    @Test
    public void testCreateView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        assertEquals(storedSql(), Map.of(SOURCE, "SELECT 1 AS x"));
    }

    @Test
    public void testCreateReplacesView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("s", "source", definition("SELECT 2 AS x"));
        assertEquals(storedSql(), Map.of(SOURCE, "SELECT 2 AS x"));
    }

    @Test
    public void testGetViewsAfterReconnect() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("t", "target", definition("SELECT 2 AS x"));
        client = new TrackingJdbcClient();
        assertEquals(storedSql(), Map.of(SOURCE, "SELECT 1 AS x", TARGET, "SELECT 2 AS x"));
    }

    @Test
    public void testDropView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("t", "target", definition("SELECT 2 AS x"));
        client.dropView("s", "source");
        assertEquals(storedSql(), Map.of(TARGET, "SELECT 2 AS x"));
    }

    @Test
    public void testDropMissingView() {
        client.dropView("s", "missing");
        assertEquals(client.getViews(), Map.of());
    }

    @Test
    public void testRenameView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.renameView("s", "source", "t", "target");
        assertEquals(storedSql(), Map.of(TARGET, "SELECT 1 AS x"));
    }

    @Test
    public void testRenameDoesNotOverwriteExistingView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("t", "target", definition("SELECT 2 AS x"));
        TrinoException e = expectThrows(TrinoException.class, () -> client.renameView("s", "source", "t", "target"));
        assertEquals(e.getErrorCode(), ALREADY_EXISTS.toErrorCode());
        assertEquals(storedSql(), Map.of(SOURCE, "SELECT 1 AS x", TARGET, "SELECT 2 AS x"));
    }

    @Test
    public void testRenameMissingSource() {
        expectThrows(ViewNotFoundException.class, () -> client.renameView("s", "source", "t", "target"));
        assertEquals(client.getViews(), Map.of());
    }

    @Test
    public void testClosesResources() throws SQLException {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("t", "target", definition("SELECT 2 AS x"));
        client.getViews();
        expectThrows(TrinoException.class, () -> client.renameView("s", "source", "t", "target"));
        expectThrows(ViewNotFoundException.class, () -> client.renameView("s", "missing", "t", "other"));
        client.dropView("t", "target");
        assertTrue(resources.stream().anyMatch(ResultSet.class::isInstance));
        for (Object resource : resources) assertTrue(isClosed(resource), "Not closed: " + resource);
    }

    private Map<SchemaTableName, String> storedSql() {
        return client.getViews().entrySet().stream().collect(toMap(Map.Entry::getKey, e -> e.getValue().getOriginalSql()));
    }

    private static ConnectorViewDefinition definition(String sql) {
        return new ConnectorViewDefinition(sql, Optional.empty(), Optional.empty(), List.of(new ViewColumn("x", TypeId.of("integer"), Optional.empty())), Optional.empty(), Optional.empty(), false, List.of());
    }

    private static boolean isClosed(Object resource) throws SQLException {
        return switch (resource) {
            case Connection c -> c.isClosed();
            case Statement s -> s.isClosed();
            case ResultSet r -> r.isClosed();
            default -> throw new IllegalArgumentException("Unexpected resource: " + resource);
        };
    }

    // records every connection, statement and result set the client opens, so tests can check each was closed
    private Object track(Object resource, Class<?> type) {
        resources.add(resource);
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object result;
            try {
                result = method.invoke(resource, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            return (result instanceof Statement || result instanceof ResultSet) ? track(result, method.getReturnType()) : result;
        });
    }

    private class TrackingJdbcClient extends ViewZooJdbcClient {

        TrackingJdbcClient() {
            super(config, new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).registerModule(new Jdk8Module()));
        }

        @Override
        public Connection getConnection() throws SQLException {
            return (Connection) track(super.getConnection(), Connection.class);
        }

    }

}
