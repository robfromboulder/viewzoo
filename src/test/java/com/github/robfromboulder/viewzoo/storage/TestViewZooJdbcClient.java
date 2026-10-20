// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.github.robfromboulder.viewzoo.config.ViewZooJdbcConfig;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.ViewNotFoundException;
import io.trino.spi.type.TypeId;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TestViewZooJdbcClient {

    private static final SchemaTableName SOURCE = new SchemaTableName("s", "source");
    private static final SchemaTableName TARGET = new SchemaTableName("t", "target");

    private PostgreSQLContainer postgres;
    private ViewZooJdbcConfig config;
    private final List<Object> resources = new ArrayList<>();
    private boolean failReads;
    private ViewZooJdbcClient client;

    @BeforeClass(groups = "integration")
    public void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:16");
        postgres.start();
        config = new ViewZooJdbcConfig().setJdbcUrl(postgres.getJdbcUrl()).setJdbcUser(postgres.getUsername()).setJdbcPassword(postgres.getPassword());
    }

    @AfterClass(alwaysRun = true, groups = "integration")
    public void stopPostgres() {
        if (postgres != null) postgres.stop();
        postgres = null;
    }

    @BeforeMethod(groups = "integration")
    public void setUp() throws SQLException {
        executeSql("DROP TABLE IF EXISTS viewzoo");
        resources.clear();
        failReads = false;
        client = new TrackingJdbcClient();
    }

    @Test(groups = "integration")
    public void testCreatesTable() {
        assertEquals(client.getViews(), Map.of());
    }

    @Test(groups = "integration")
    public void testCreateView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        assertStored(Map.of(SOURCE, "SELECT 1 AS x"));
    }

    @Test(groups = "integration")
    public void testCreateReplacesView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("s", "source", definition("SELECT 2 AS x"));
        assertStored(Map.of(SOURCE, "SELECT 2 AS x"));
    }

    @Test(groups = "integration")
    public void testConstructorKeepsExistingViews() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("t", "target", definition("SELECT 2 AS x"));
        client = new TrackingJdbcClient();
        assertStored(Map.of(SOURCE, "SELECT 1 AS x", TARGET, "SELECT 2 AS x"));
    }

    @Test(groups = "integration")
    public void testDropView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("t", "target", definition("SELECT 2 AS x"));
        client.dropView("s", "source");
        assertStored(Map.of(TARGET, "SELECT 2 AS x"));
    }

    @Test(groups = "integration")
    public void testDropMissingView() {
        client.dropView("s", "missing");
        assertEquals(client.getViews(), Map.of());
    }

    @Test(groups = "integration")
    public void testRenameView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.renameView("s", "source", "t", "target");
        assertStored(Map.of(TARGET, "SELECT 1 AS x"));
    }

    @Test(groups = "integration")
    public void testRenameDoesNotOverwriteExistingView() {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        client.createView("t", "target", definition("SELECT 2 AS x"));
        TrinoException e = expectThrows(TrinoException.class, () -> client.renameView("s", "source", "t", "target"));
        assertEquals(e.getErrorCode(), ALREADY_EXISTS.toErrorCode());
        assertStored(Map.of(SOURCE, "SELECT 1 AS x", TARGET, "SELECT 2 AS x"));
    }

    @Test(groups = "integration")
    public void testRenameMissingSource() {
        expectThrows(ViewNotFoundException.class, () -> client.renameView("s", "source", "t", "target"));
        assertEquals(client.getViews(), Map.of());
    }

    @Test(groups = "integration")
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

    @Test(groups = "integration")
    public void testClosesResourcesOnSkippedRow() throws SQLException {
        insertRow("s", "source", "not json");
        assertEquals(client.getViews(), Map.of());
        assertTrue(resources.stream().anyMatch(ResultSet.class::isInstance));
        for (Object resource : resources) assertTrue(isClosed(resource), "Not closed: " + resource);
    }

    @Test(groups = "integration")
    public void testClosesResourcesOnReadFailure() throws SQLException {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        failReads = true;
        assertKeepsCause(expectThrows(TrinoException.class, () -> client.getViews()));
        assertTrue(resources.stream().anyMatch(ResultSet.class::isInstance));
        for (Object resource : resources) assertTrue(isClosed(resource), "Not closed: " + resource);
    }

    @DataProvider
    public Object[][] unreadableDefinitions() {
        return new Object[][]{{"not json"}, {null}, {"null"}, {"{}"}};
    }

    @Test(groups = "integration", dataProvider = "unreadableDefinitions")
    public void testUnreadableRowSkipped(String definition) throws SQLException {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        insertRow("s", "bad", definition);
        assertStored(Map.of(SOURCE, "SELECT 1 AS x"));
    }

    @DataProvider
    public Object[][] unloadableNames() {
        return new Object[][]{{"", "source"}, {"s", ""}, {"S", "source"}, {"s", "Source"}};
    }

    // an uppercase row would load under the lowercase name of the view beside it
    @Test(groups = "integration", dataProvider = "unloadableNames")
    public void testUnloadableNameSkipped(String schema, String view) throws Exception {
        client.createView("s", "source", definition("SELECT 1 AS x"));
        insertRow(schema, view, ViewZooStorageClientProvider.createObjectMapper().writeValueAsString(definition("SELECT 2 AS x")));
        assertStored(Map.of(SOURCE, "SELECT 1 AS x"));
    }

    @Test(groups = "integration")
    public void testStorageFailuresKeepCause() throws SQLException {
        executeSql("DROP TABLE viewzoo");
        assertKeepsCause(expectThrows(TrinoException.class, () -> client.getViews()));
        assertKeepsCause(expectThrows(TrinoException.class, () -> client.createView("s", "source", definition("SELECT 1 AS x"))));
        assertKeepsCause(expectThrows(TrinoException.class, () -> client.dropView("s", "source")));
    }

    @Test(groups = "integration")
    public void testConstructorFailureKeepsCause() {
        ViewZooJdbcConfig wrongPassword = new ViewZooJdbcConfig().setJdbcUrl(postgres.getJdbcUrl()).setJdbcUser(postgres.getUsername()).setJdbcPassword("wrong");
        assertKeepsCause(expectThrows(TrinoException.class, () -> new ViewZooJdbcClient(wrongPassword, ViewZooStorageClientProvider.createObjectMapper())));
    }

    private static void assertKeepsCause(TrinoException e) {
        assertTrue(e.getCause() instanceof SQLException, "Expected a SQLException cause: " + e.getCause());
        assertTrue(e.getMessage().endsWith(": " + e.getCause().getMessage()), "Expected the cause's message: " + e.getMessage());
    }

    private void insertRow(String schema, String view, String definition) throws SQLException {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement statement = connection.prepareStatement("INSERT INTO viewzoo VALUES (?, ?, ?)")) {
            statement.setString(1, schema);
            statement.setString(2, view);
            statement.setString(3, definition);
            statement.executeUpdate();
        }
    }

    private void executeSql(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertStored(Map<SchemaTableName, String> expectedSql) {
        Map<SchemaTableName, List<Object>> actual = client.getViews().entrySet().stream().collect(toMap(Map.Entry::getKey, e -> fields(e.getValue())));
        Map<SchemaTableName, List<Object>> expected = expectedSql.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> fields(definition(e.getValue()))));
        assertEquals(actual, expected);
    }

    private static ConnectorViewDefinition definition(String sql) {
        ViewColumn column = new ViewColumn("x", TypeId.of("integer"), Optional.of("column comment"));
        return new ConnectorViewDefinition(sql, Optional.of("c"), Optional.of("s"), List.of(column), Optional.of("view comment"), Optional.of("owner"), false, List.of(new CatalogSchemaName("c", "p")));
    }

    // ConnectorViewDefinition and ViewColumn don't implement equals
    private static List<Object> fields(ConnectorViewDefinition d) {
        List<Object> columns = d.getColumns().stream().map(c -> (Object) List.of(c.getName(), c.getType(), c.getComment())).toList();
        return List.of(d.getOriginalSql(), d.getCatalog(), d.getSchema(), columns, d.getComment(), d.getOwner(), d.isRunAsInvoker(), d.getPath());
    }

    private static boolean isClosed(Object resource) throws SQLException {
        return switch (resource) {
            case Connection c -> c.isClosed();
            case Statement s -> s.isClosed();
            case ResultSet r -> r.isClosed();
            default -> throw new IllegalArgumentException("Unexpected resource: " + resource);
        };
    }

    // records every connection, statement and result set the client opens, so tests can check each was closed;
    // failReads makes every ResultSet.getString throw
    private Object track(Object resource, Class<?> type) {
        resources.add(resource);
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (failReads && resource instanceof ResultSet && method.getName().equals("getString")) throw new SQLException("Injected read failure");
            Object result;
            try {
                result = method.invoke(resource, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            Class<?> returnType = method.getReturnType();
            boolean trackable = returnType.isInterface() && (Statement.class.isAssignableFrom(returnType) || ResultSet.class.isAssignableFrom(returnType));
            return (trackable && result != null && !resources.contains(result)) ? track(result, returnType) : result;
        });
    }

    private class TrackingJdbcClient extends ViewZooJdbcClient {

        TrackingJdbcClient() {
            super(config, ViewZooStorageClientProvider.createObjectMapper());
        }

        @Override
        public Connection getConnection() throws SQLException {
            return (Connection) track(super.getConnection(), Connection.class);
        }

    }

}
