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
        File pluginDir = new File(projectDir, "target/viewzoo-478");
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
    public void testVirtualViews(String catalog) throws Exception {
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            String viewName = catalog + ".example.hello";

            // create the view
            statement.execute("CREATE VIEW " + viewName + " AS SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS t (key, value)");

            // query the view and verify data
            try (ResultSet resultSet = statement.executeQuery("SELECT * FROM " + viewName + " ORDER BY key")) {
                assertTrue(resultSet.next(), "Expected first row");
                assertEquals(resultSet.getInt("key"), 1);
                assertEquals(resultSet.getString("value"), "a");
                assertTrue(resultSet.next(), "Expected second row");
                assertEquals(resultSet.getInt("key"), 2);
                assertEquals(resultSet.getString("value"), "b");
                assertFalse(resultSet.next(), "Expected no more rows");
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