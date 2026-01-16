// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestViewZooIntegration {

    private static final String TRINO_JDBC_URL = "jdbc:trino://localhost:7721";
    private static final int TRINO_MAX_WAIT_SECONDS = 30;
    private static final String TRINO_USER = "test";

    private File projectDir;
    private File dockerDir;

    @BeforeClass(groups = "integration")
    public void setUp() throws Exception {
        projectDir = new File(System.getProperty("user.dir"));
        dockerDir = new File(projectDir, "src/test/docker");

        // verify docker-compose.yml exists
        File dockerComposeFile = new File(dockerDir, "docker-compose.yml");
        assertTrue(dockerComposeFile.exists(), "docker-compose.yml not found in src/test/docker");

        // verify the plugin has been built
        File pluginDir = new File(projectDir, "target/viewzoo-479");
        assertTrue(pluginDir.exists(), "Plugin not built. Please run 'mvn clean package' first.");

        // start Postgresql & Trino services on private ports
        runCommand("docker", "compose", "up", "-d");
        waitForTrino();
    }

    @AfterClass(alwaysRun = true, groups = "integration")
    public void tearDown() throws Exception {
        // stop Postgresql & Trino services, remove volumes
        runCommand("docker", "compose", "down", "-v");
    }

    @DataProvider(name = "catalogs")
    public Object[][] catalogProvider() {
        return new Object[][]{{"testjdbc"}, {"testfs"}};
    }

    @Test(groups = "integration", dataProvider = "catalogs")
    public void testViewLifecycle(String catalog) throws Exception {
        String viewName = catalog + ".example.hello";

        // exercise view functions for specified catalog
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {

            // create the view
            String create_view = "CREATE VIEW " + viewName + " AS SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS t (key, value)";
            statement.execute(create_view);

            // query the view and verify data
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + viewName + " ORDER BY key")) {
                assertTrue(rs.next(), "Expected first row");
                assertEquals(rs.getInt("key"), 1);
                assertEquals(rs.getString("value"), "a");
                assertTrue(rs.next(), "Expected second row");
                assertEquals(rs.getInt("key"), 2);
                assertEquals(rs.getString("value"), "b");
                assertFalse(rs.next(), "Expected no more rows");
            }

            // creating the view again should fail
            try {
                statement.execute(create_view);
                throw new AssertionError("Expected CREATE VIEW to fail when view already exists");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("already exists"), "Expected 'already exists' error but got: " + e.getMessage());
            }

            // replace the existing view and verify new data
            statement.execute("CREATE OR REPLACE VIEW " + viewName + " AS SELECT * FROM (VALUES (3, 'c')) AS t (key, value)");
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + viewName + " ORDER BY key")) {
                assertTrue(rs.next(), "Expected one row after replace");
                assertEquals(rs.getInt("key"), 3);
                assertEquals(rs.getString("value"), "c");
                assertFalse(rs.next(), "Expected no more rows after replace");
            }

            // drop the view
            statement.execute("DROP VIEW " + viewName);

            // verify the view no longer exists
            try {
                statement.executeQuery("SELECT * FROM " + viewName);
                throw new AssertionError("Expected query to fail after DROP VIEW");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("does not exist"), "Expected 'does not exist' error but got: " + e.getMessage());
            }

            // re-add previously defined view and verify new data
            statement.execute("CREATE OR REPLACE VIEW " + viewName + " AS SELECT * FROM (VALUES (4, 'D')) AS t (key, value)");
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + viewName + " ORDER BY key")) {
                assertTrue(rs.next(), "Expected one row after replace");
                assertEquals(rs.getInt("key"), 4);
                assertEquals(rs.getString("value"), "D");
                assertFalse(rs.next(), "Expected no more rows after replace");
            }
        }

        // restart Postgresql & Trino services
        runCommand("docker", "compose", "restart");
        waitForTrino();

        // read view defined before restart and verify data
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + viewName + " ORDER BY key")) {
                assertTrue(rs.next(), "Expected one row after restart");
                assertEquals(rs.getInt("key"), 4);
                assertEquals(rs.getString("value"), "D");
                assertFalse(rs.next(), "Expected no more rows after restart");
            }
        }
    }

    @Test(groups = "integration", dataProvider = "catalogs")
    public void testViewsWithLongNames(String catalog) throws Exception {
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {

            // test schema name too long (101 characters)
            String longSchema = "a".repeat(101);
            String viewWithLongSchema = catalog + "." + longSchema + ".test";
            try {
                statement.execute("CREATE VIEW " + viewWithLongSchema + " AS SELECT 1 as x");
                throw new AssertionError("Expected CREATE VIEW to fail with long schema name");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("exceeds maximum length"), "Expected 'exceeds maximum length' error but got: " + e.getMessage());
            }

            // test table name too long (101 characters)
            String longTable = "b".repeat(101);
            String viewWithLongTable = catalog + ".example." + longTable;
            try {
                statement.execute("CREATE VIEW " + viewWithLongTable + " AS SELECT 1 as x");
                throw new AssertionError("Expected CREATE VIEW to fail with long table name");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("exceeds maximum length"), "Expected 'exceeds maximum length' error but got: " + e.getMessage());
            }

            // test max allowed lengths (100 characters each)
            String maxSchema = "s" + "x".repeat(99);
            String maxTable = "t" + "y".repeat(99);
            String viewAtMaxLength = catalog + "." + maxSchema + "." + maxTable;
            statement.execute("CREATE VIEW " + viewAtMaxLength + " AS SELECT 1 as x");
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + viewAtMaxLength)) {
                assertTrue(rs.next(), "Expected one row");
                assertEquals(rs.getInt("x"), 1);
            } finally {
                statement.execute("DROP VIEW " + viewAtMaxLength);
            }
        }
    }

    @Test(groups = "integration", dataProvider = "catalogs")
    public void testViewsWithInvalidNames(String catalog) throws Exception {
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {

            // test schema name containing period
            String schemaWithPeriod = "\"invalid.schema\"";
            String viewWithInvalidSchema = catalog + "." + schemaWithPeriod + ".test";
            try {
                statement.execute("CREATE VIEW " + viewWithInvalidSchema + " AS SELECT 1 as x");
                throw new AssertionError("Expected CREATE VIEW to fail with period in schema name");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("Invalid schema name"), "Expected 'Invalid schema name' error but got: " + e.getMessage());
            }

            // test table name containing period
            String tableWithPeriod = "\"invalid.table\"";
            String viewWithInvalidTable = catalog + ".example." + tableWithPeriod;
            try {
                statement.execute("CREATE VIEW " + viewWithInvalidTable + " AS SELECT 1 as x");
                throw new AssertionError("Expected CREATE VIEW to fail with period in table name");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("Invalid table name"), "Expected 'Invalid table name' error but got: " + e.getMessage());
            }
        }
    }

    private void runCommand(String... command) throws Exception {
        // run process
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(dockerDir);
        processBuilder.environment().put("PROJECT_ROOT", projectDir.getAbsolutePath());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        // echo process output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        // check process exit code
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
    }

    private void waitForTrino() throws Exception {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < TimeUnit.SECONDS.toMillis(TRINO_MAX_WAIT_SECONDS)) {
            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                if (!resultSet.next()) continue;
                boolean fs_ready = false;
                boolean jdbc_ready = false;
                try (ResultSet catalogs = statement.executeQuery("SHOW CATALOGS")) {
                    while (catalogs.next()) {
                        String catalog = catalogs.getString(1);
                        if ("testfs".equals(catalog)) fs_ready = true;
                        if ("testjdbc".equals(catalog)) jdbc_ready = true;
                    }
                }
                if (jdbc_ready && fs_ready) return;
            } catch (SQLException e) {
                // ignore, Trino not ready yet
            }
            TimeUnit.SECONDS.sleep(1);
        }
        throw new RuntimeException("Trino did not become ready within " + TRINO_MAX_WAIT_SECONDS + " seconds");
    }

}