// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestViewZooIntegration {

    private static final String MOUNT_DIR = "/var/viewzoo";
    private static final String TRINO_JDBC_URL = "jdbc:trino://localhost:7721";
    private static final int TRINO_MAX_WAIT_SECONDS = 30;
    private static final String TRINO_USER = "test";
    private static final Set<String> CATALOGS = Set.of("testjdbc", "testfs", "testfsmount");
    private static final String[] PORTABLE_NAMES = {"a b", "a-b_c", "a\\b", "a:b", "a*b?", "a<b>|", "a\"b", "a'b%", "a\tb", "a\nb", "café", "日本", "🐧", " lead", "trail "};

    private File projectDir;
    private File dockerDir;
    private Path mountDir;

    @BeforeClass(groups = "integration")
    public void setUp() throws Exception {
        projectDir = new File(System.getProperty("user.dir"));
        dockerDir = new File(projectDir, "src/test/docker");

        // verify docker-compose.yml exists
        File dockerComposeFile = new File(dockerDir, "docker-compose.yml");
        assertTrue(dockerComposeFile.exists(), "docker-compose.yml not found in src/test/docker");

        // verify the plugin has been built
        File pluginDir = new File(projectDir, "target/viewzoo-483");
        assertTrue(pluginDir.exists(), "Plugin not built. Please run 'mvn clean package' first.");

        // empty, world-writable host directory for testfsmount, since the container's trino user may not own it
        mountDir = projectDir.toPath().resolve("target/testfsmount");
        if (Files.exists(mountDir)) {
            try (Stream<Path> paths = Files.walk(mountDir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.createDirectories(mountDir);
        Files.setPosixFilePermissions(mountDir, PosixFilePermissions.fromString("rwxrwxrwx"));

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
        return new Object[][]{{"testjdbc"}, {"testfs"}, {"testfsmount"}};
    }

    // testfs keeps views inside the Linux container; testfsmount keeps them in a host directory, which is case-insensitive
    // when the host is macOS
    @DataProvider(name = "fsCatalogs")
    public Object[][] fsCatalogProvider() {
        return new Object[][]{{"testfs", "/home/trino"}, {"testfsmount", MOUNT_DIR}};
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
    public void testViewRenaming(String catalog) throws Exception {
        String original = catalog + ".renaming.original";
        String renamed = catalog + ".renaming.renamed";
        String moved = catalog + ".renamed.moved";
        String other = catalog + ".renaming.other";

        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE VIEW " + original + " AS SELECT * FROM (VALUES (1, 'a')) AS t (key, value)");
            statement.execute("CREATE VIEW " + other + " AS SELECT 2 AS key");

            // rename within schema, verify data follows and old name is gone
            statement.execute("ALTER VIEW " + original + " RENAME TO " + renamed);
            assertSingleRow(statement, renamed, 1, "a");
            assertViewMissing(statement, original);

            // rename across schemas
            statement.execute("ALTER VIEW " + renamed + " RENAME TO " + moved);
            assertSingleRow(statement, moved, 1, "a");
            assertViewMissing(statement, renamed);

            // renaming onto an existing view should fail
            try {
                statement.execute("ALTER VIEW " + moved + " RENAME TO " + other);
                throw new AssertionError("Expected ALTER VIEW to fail when target view already exists");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("already exists"), "Expected 'already exists' error but got: " + e.getMessage());
            }

            // renaming a missing view should fail
            try {
                statement.execute("ALTER VIEW " + original + " RENAME TO " + catalog + ".renaming.nowhere");
                throw new AssertionError("Expected ALTER VIEW to fail when source view does not exist");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("does not exist"), "Expected 'does not exist' error but got: " + e.getMessage());
            }

            // renaming to an invalid name should fail
            try {
                statement.execute("ALTER VIEW " + moved + " RENAME TO " + catalog + ".renaming.\"invalid.table\"");
                throw new AssertionError("Expected ALTER VIEW to fail with period in table name");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("Invalid table name"), "Expected 'Invalid table name' error but got: " + e.getMessage());
            }
        }

        // restart Postgresql & Trino services
        runCommand("docker", "compose", "restart");
        waitForTrino();

        // verify renamed view persisted under its new name only
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            assertSingleRow(statement, moved, 1, "a");
            assertViewMissing(statement, original);
            assertViewMissing(statement, renamed);
            statement.execute("DROP VIEW " + moved);
            statement.execute("DROP VIEW " + other);
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

    @Test(groups = "integration", dataProvider = "catalogs")
    public void testViewNameCharacters(String catalog) throws Exception {
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            for (String name : PORTABLE_NAMES) {
                String inSchema = catalog + "." + quote(name) + ".t";
                String inTable = catalog + ".chars." + quote(name);
                String renamed = catalog + ".chars." + quote(name + "2");
                try {
                    statement.execute("CREATE VIEW " + inSchema + " AS SELECT 1 AS x");
                    statement.execute("CREATE VIEW " + inTable + " AS SELECT 1 AS x");
                    assertSingleValue(statement, inSchema);
                    assertSingleValue(statement, inTable);
                    statement.execute("ALTER VIEW " + inTable + " RENAME TO " + renamed);
                    assertSingleValue(statement, renamed);
                    assertViewMissing(statement, inTable);
                    statement.execute("DROP VIEW " + inSchema);
                    statement.execute("DROP VIEW " + renamed);
                    assertViewMissing(statement, inSchema);
                    assertViewMissing(statement, renamed);
                } finally {
                    dropViews(statement, inSchema, inTable, renamed);
                }
            }

            // unquoted and quoted identifiers are both lowercased before reaching the connector
            try {
                statement.execute("CREATE VIEW " + catalog + ".chars.\"MiXeD\" AS SELECT 1 AS x");
                assertSingleValue(statement, catalog + ".chars.mixed");
            } finally {
                dropViews(statement, catalog + ".chars.mixed");
            }
        }
    }

    @DataProvider(name = "storageSpecificNames")
    public Object[][] storageSpecificNameProvider() {
        // expected error fragment, or null when the name is accepted
        return new Object[][]{
                {"testjdbc", "a/b", null},
                {"testjdbc", "/abs", null},
                {"testjdbc", "a\u0000b", "invalid byte sequence"},
                {"testjdbc", "日".repeat(100), null},
                {"testjdbc", "cafe\u0301", null},
                {"testfs", "a/b", "not supported by filesystem storage"},
                {"testfs", "/abs", "not supported by filesystem storage"},
                {"testfs", "a\u0000b", "not supported by filesystem storage"},
                {"testfs", "cafe\u0301", "not supported by filesystem storage"},
                // 300 bytes in UTF-8 exceeds the 255-byte filename limit of Linux filesystems
                {"testfs", "日".repeat(100), "too long for filesystem storage"},
                {"testfsmount", "a/b", "not supported by filesystem storage"},
                {"testfsmount", "/abs", "not supported by filesystem storage"},
                {"testfsmount", "a\u0000b", "not supported by filesystem storage"},
                {"testfsmount", "cafe\u0301", "not supported by filesystem storage"},
                {"testfsmount", "日".repeat(100), "too long for filesystem storage"}};
    }

    @Test(groups = "integration", dataProvider = "storageSpecificNames")
    public void testViewNameStorageLimits(String catalog, String name, String expectedError) throws Exception {
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            for (String view : new String[]{catalog + "." + quote(name) + ".t", catalog + ".limits." + quote(name)}) {
                try {
                    if (expectedError == null) {
                        statement.execute("CREATE VIEW " + view + " AS SELECT 1 AS x");
                        assertSingleValue(statement, view);
                    } else {
                        try {
                            statement.execute("CREATE VIEW " + view + " AS SELECT 1 AS x");
                            throw new AssertionError("Expected CREATE VIEW to fail for " + view);
                        } catch (SQLException e) {
                            assertTrue(e.getMessage().contains(expectedError), "Expected '" + expectedError + "' error but got: " + e.getMessage());
                        }
                    }
                } finally {
                    dropViews(statement, view);
                }
            }
        }
    }

    @Test(groups = "integration", dataProvider = "catalogs")
    public void testViewNamesSurviveRestart(String catalog) throws Exception {
        String source = catalog + ".rename.source";
        String slashTarget = catalog + ".rename.\"a/b\"";
        List<String> views = new ArrayList<>(List.of(source, slashTarget));
        for (String name : PORTABLE_NAMES) {
            views.add(catalog + "." + quote(name) + ".r");
            views.add(catalog + ".restart." + quote(name));
        }
        try {
            restartWithViews(catalog, source, slashTarget);
        } finally {
            // fresh connection, since the restart drops any that were open
            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                dropViews(statement, views.toArray(String[]::new));
            } catch (SQLException e) {
                System.err.println("Failed to connect to drop views: " + e.getMessage());
            }
        }
    }

    private void restartWithViews(String catalog, String source, String slashTarget) throws Exception {
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            for (String name : PORTABLE_NAMES) {
                statement.execute("CREATE VIEW " + catalog + "." + quote(name) + ".r AS SELECT 1 AS x");
                statement.execute("CREATE VIEW " + catalog + ".restart." + quote(name) + " AS SELECT 1 AS x");
            }
            statement.execute("CREATE VIEW " + source + " AS SELECT 1 AS x");
            if (catalog.startsWith("testfs")) {
                try {
                    statement.execute("ALTER VIEW " + source + " RENAME TO " + slashTarget);
                    throw new AssertionError("Expected ALTER VIEW to fail for " + slashTarget);
                } catch (SQLException e) {
                    assertTrue(e.getMessage().contains("not supported by filesystem storage"), "Expected 'not supported by filesystem storage' error but got: " + e.getMessage());
                }
                assertSingleValue(statement, source);
            } else {
                statement.execute("ALTER VIEW " + source + " RENAME TO " + slashTarget);
                assertSingleValue(statement, slashTarget);
            }
        }

        // restart Postgresql & Trino services
        runCommand("docker", "compose", "restart");
        waitForTrino();

        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            for (String name : PORTABLE_NAMES) {
                String inSchema = catalog + "." + quote(name) + ".r";
                String inTable = catalog + ".restart." + quote(name);
                assertSingleValue(statement, inSchema);
                assertSingleValue(statement, inTable);
                statement.execute("DROP VIEW " + inSchema);
                statement.execute("DROP VIEW " + inTable);
            }
            String survivor = catalog.startsWith("testfs") ? source : slashTarget;
            assertSingleValue(statement, survivor);
            statement.execute("DROP VIEW " + survivor);
        }
    }

    @Test(groups = "integration", dataProvider = "fsCatalogs")
    public void testFileNameCollision(String catalog, String dir) throws Exception {
        String foreign = dir + "/Collide.T.json";
        String source = catalog + ".collide.source";
        String target = catalog + ".collide.t";
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            try {
                String json = viewJson(statement, catalog, dir);
                writeContainerFile(foreign, json);
                boolean caseInsensitive = containerFileExists(dir + "/collide.t.json");
                System.out.println(catalog + " directory is case-" + (caseInsensitive ? "insensitive" : "sensitive"));
                statement.execute("CREATE VIEW " + source + " AS SELECT 1 AS x");
                if (caseInsensitive) {
                    assertFails(statement, "CREATE VIEW " + target + " AS SELECT 1 AS x", "conflicts with an existing view file");
                    assertFails(statement, "ALTER VIEW " + source + " RENAME TO " + target, "conflicts with an existing view file");
                    assertViewMissing(statement, target);
                } else {
                    statement.execute("CREATE VIEW " + target + " AS SELECT 1 AS x");
                    assertSingleValue(statement, target);
                }
                assertEquals(readContainerFile(foreign), json);
            } finally {
                dropViews(statement, source, target, catalog + ".template.v");
                runContainer(null, "rm", "-f", foreign);
            }
        }
    }

    @Test(groups = "integration", dataProvider = "fsCatalogs")
    public void testUnsupportedFileNamesSkipped(String catalog, String dir) throws Exception {
        String nfd = "cafe\u0301";
        List<String> skipped = List.of("Hidden.Upper.json", nfd + ".t.json", "hidden..json", "hidden.a.b.json");
        List<String> files = new ArrayList<>(skipped);
        files.add("hidden.ok.json");
        try {
            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                String json = viewJson(statement, catalog, dir);
                for (String file : files) writeContainerFile(dir + "/" + file, json);
            }
            assertTrue(runContainer(null, "ls", dir).contains(nfd + ".t.json"), "Expected Linux to keep the NFD file name");

            runCommand("docker", "compose", "restart", "trino");
            waitForTrino();

            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                assertSingleValue(statement, catalog + ".hidden.ok");
                assertViewMissing(statement, catalog + ".hidden.upper");
                assertViewMissing(statement, catalog + "." + quote(nfd) + ".t");
                assertViewMissing(statement, catalog + ".\"café\".t");
            }
            String logs = trinoLogs();
            for (String file : skipped) {
                assertTrue(logs.contains("Skipping file whose name is not a view name Trino can create: " + dir + "/" + file), "Expected warning for " + file);
            }
        } finally {
            for (String file : files) runContainer(null, "rm", "-f", dir + "/" + file);
            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                dropViews(statement, catalog + ".hidden.ok", catalog + ".template.v");
            }
        }
    }

    @DataProvider(name = "unreadableFiles")
    public Object[][] unreadableFileProvider() {
        List<Object[]> cases = new ArrayList<>();
        for (Object[] fs : fsCatalogProvider()) {
            cases.add(new Object[]{fs[0], fs[1], "corrupt", "not json"});
            cases.add(new Object[]{fs[0], fs[1], "nulldef", "null"});
        }
        return cases.toArray(Object[][]::new);
    }

    @Test(groups = "integration", dataProvider = "unreadableFiles")
    public void testUnreadableFileSkipped(String catalog, String dir, String table, String contents) throws Exception {
        String file = dir + "/hidden." + table + ".json";
        try {
            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE OR REPLACE VIEW " + catalog + ".hidden.ok AS SELECT 1 AS x");
                writeContainerFile(file, contents);
            }

            runCommand("docker", "compose", "restart", "trino");
            waitForTrino();

            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                assertSingleValue(statement, catalog + ".hidden.ok");
                assertEquals(listedViews(statement, catalog, "hidden"), List.of("ok"));
            }
            assertTrue(trinoLogs().contains("Skipping file whose contents are not a view definition: " + file), "Expected warning for " + file);
        } finally {
            removeContainerFile(file);
            recoverTrino();
            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                dropViews(statement, catalog + ".hidden.ok");
            }
        }
    }

    @Test(groups = "integration")
    public void testUnloadableRowsSkipped() throws Exception {
        List<String> warnings = List.of(
                "Skipping row whose definition is not a view definition: hidden.corrupt",
                "Skipping row whose definition is not a view definition: hidden.nulldef",
                "Skipping row whose name is not a view name Trino can create: .empty",
                "Skipping row whose name is not a view name Trino can create: Hidden.Ok");
        try {
            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE OR REPLACE VIEW testjdbc.hidden.ok AS SELECT 1 AS x");
            }
            // rows with bad names copy a valid definition, so only the name can cause the skip
            runPostgres("INSERT INTO viewzoo VALUES ('hidden', 'corrupt', 'not json'), ('hidden', 'nulldef', NULL)");
            runPostgres("INSERT INTO viewzoo SELECT n.schema, n.view_name, v.definition FROM viewzoo v, (VALUES ('', 'empty'), ('Hidden', 'Ok')) AS n(schema, view_name) WHERE v.schema = 'hidden' AND v.view_name = 'ok'");

            runCommand("docker", "compose", "restart", "trino");
            waitForTrino();

            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                assertSingleValue(statement, "testjdbc.hidden.ok");
                assertEquals(listedViews(statement, "testjdbc", "hidden"), List.of("ok"));
            }
            String logs = trinoLogs();
            for (String warning : warnings) assertTrue(logs.contains(warning), "Expected warning: " + warning);
        } finally {
            runPostgres("DELETE FROM viewzoo WHERE (schema, view_name) IN (('hidden', 'corrupt'), ('hidden', 'nulldef'), ('', 'empty'), ('Hidden', 'Ok'))");
            recoverTrino();
            try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
                 Statement statement = connection.createStatement()) {
                dropViews(statement, "testjdbc.hidden.ok");
            }
        }
    }

    // every catalog with its view directory, which testjdbc lacks
    @DataProvider(name = "storages")
    public Object[][] storageProvider() {
        return Stream.concat(Stream.<Object[]>of(new Object[]{"testjdbc", null}), Stream.of(fsCatalogProvider())).toArray(Object[][]::new);
    }

    // the view is written to storage after the cache loaded, as another coordinator or an outside change would
    @Test(groups = "integration", dataProvider = "storages")
    public void testCreateRefusesViewAddedToStorage(String catalog, String dir) throws Exception {
        String file = (dir == null) ? null : dir + "/behind.v.json";
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            try {
                if (file == null) {
                    statement.execute("CREATE OR REPLACE VIEW testjdbc.template.v AS SELECT 1 AS x");
                    runPostgres("INSERT INTO viewzoo SELECT 'behind', 'v', definition FROM viewzoo WHERE schema = 'template' AND view_name = 'v'");
                } else {
                    writeContainerFile(file, viewJson(statement, catalog, dir));
                }
                String stored = storedBehindView(file);
                // a cached view would be refused by the cache check, never reaching storage
                assertViewMissing(statement, catalog + ".behind.v");
                assertFails(statement, "CREATE VIEW " + catalog + ".behind.v AS SELECT 2 AS x", "already exists");
                assertEquals(storedBehindView(file), stored);
            } finally {
                // first, since storage cleanup can throw; behind.v is cached only if the create wrongly succeeded
                dropViews(statement, catalog + ".template.v", catalog + ".behind.v");
                if (file == null) runPostgres("DELETE FROM viewzoo WHERE schema = 'behind' AND view_name = 'v'");
                else removeContainerFile(file);
            }
        }
    }

    private String storedBehindView(String file) throws Exception {
        return (file == null) ? queryPostgres("SELECT definition FROM viewzoo WHERE schema = 'behind' AND view_name = 'v'") : readContainerFile(file);
    }

    // information_schema.views reads every definition, so it fails where a listing of names would not
    private static List<String> listedViews(Statement statement, String catalog, String schema) throws SQLException {
        List<String> views = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("SELECT table_name FROM " + catalog + ".information_schema.views WHERE table_schema = '" + schema + "' ORDER BY table_name")) {
            while (rs.next()) views.add(rs.getString(1));
        }
        return views;
    }

    // JSON for a view definition, as the connector itself writes it
    private String viewJson(Statement statement, String catalog, String dir) throws Exception {
        statement.execute("CREATE OR REPLACE VIEW " + catalog + ".template.v AS SELECT 1 AS x");
        return readContainerFile(dir + "/template.v.json");
    }

    private static void assertFails(Statement statement, String sql, String expectedError) {
        try {
            statement.execute(sql);
            throw new AssertionError("Expected failure for " + sql);
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains(expectedError), "Expected '" + expectedError + "' error but got: " + e.getMessage());
        }
    }

    private static void assertSingleRow(Statement statement, String viewName, int key, String value) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT * FROM " + viewName)) {
            assertTrue(rs.next(), "Expected one row in " + viewName);
            assertEquals(rs.getInt("key"), key);
            assertEquals(rs.getString("value"), value);
            assertFalse(rs.next(), "Expected no more rows in " + viewName);
        }
    }

    private static void assertSingleValue(Statement statement, String viewName) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT x FROM " + viewName)) {
            assertTrue(rs.next(), "Expected one row in " + viewName);
            assertEquals(rs.getInt("x"), 1);
            assertFalse(rs.next(), "Expected no more rows in " + viewName);
        }
    }

    private static void assertViewMissing(Statement statement, String viewName) {
        try {
            statement.executeQuery("SELECT * FROM " + viewName);
            throw new AssertionError("Expected query to fail for missing view " + viewName);
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("does not exist"), "Expected 'does not exist' error but got: " + e.getMessage());
        }
    }

    // best effort, so a failed assertion doesn't leave views behind for later tests; reports rather than throws so the
    // original failure isn't masked
    private static void dropViews(Statement statement, String... views) {
        for (String view : views) {
            try {
                statement.execute("DROP VIEW IF EXISTS " + view);
            } catch (SQLException e) {
                System.err.println("Failed to drop view " + view + ": " + e.getMessage());
            }
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private boolean containerFileExists(String path) throws Exception {
        return runContainer(null, "sh", "-c", "if [ -e \"$1\" ]; then echo yes; else echo no; fi", "sh", path).trim().equals("yes");
    }

    private String readContainerFile(String path) throws Exception {
        return runContainer(null, "cat", path);
    }

    // a catalog that fails to load stops Trino, so mounted files are removed from the host and recoverTrino discards the rest
    private void removeContainerFile(String path) throws Exception {
        if (path.startsWith(MOUNT_DIR + "/")) {
            Files.deleteIfExists(mountDir.resolve(path.substring(MOUNT_DIR.length() + 1)));
        } else if (isTrinoRunning()) {
            runContainer(null, "rm", "-f", path);
        }
    }

    private void writeContainerFile(String path, String content) throws Exception {
        runContainer(content, "sh", "-c", "cat > \"$1\"", "sh", path);
    }

    private void runPostgres(String sql) throws Exception {
        runCapture(null, "docker", "compose", "exec", "-T", "postgres", "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql);
    }

    // unaligned and without headers, so the output is the bare values
    private String queryPostgres(String sql) throws Exception {
        return runCapture(null, "docker", "compose", "exec", "-T", "postgres", "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1", "-tAc", sql);
    }

    // Trino logs to stderr, which only some Compose versions merge into the stdout of compose logs
    private String trinoLogs() throws Exception {
        return runCapture(null, "sh", "-c", "docker compose logs trino 2>&1");
    }

    private boolean isTrinoRunning() throws Exception {
        return !runCapture(null, "docker", "compose", "ps", "--status", "running", "--quiet", "trino").isBlank();
    }

    // recreating the container discards every file written inside it
    private void recoverTrino() throws Exception {
        if (isTrinoReady()) return;
        runCommand("docker", "compose", "up", "-d", "--force-recreate", "trino");
        waitForTrino();
    }

    // runs in the Trino container, so file probes see each directory exactly as the connector does
    private String runContainer(String stdin, String... command) throws Exception {
        List<String> args = new ArrayList<>(List.of("docker", "compose", "exec", "-T", "trino"));
        args.addAll(List.of(command));
        return runCapture(stdin, args.toArray(String[]::new));
    }

    // echoes merged output line by line, so slow docker commands show progress
    private void runCommand(String... command) throws Exception {
        Process process = processBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
    }

    // returns stdout alone, so stderr warnings can't corrupt captured output; a temp file holds stderr without a reader thread
    private String runCapture(String stdin, String... command) throws Exception {
        Path errors = Files.createTempFile("viewzoo-stderr", ".txt");
        try {
            Process process = processBuilder(command).redirectError(errors.toFile()).start();
            try (OutputStream in = process.getOutputStream()) {
                if (stdin != null) in.write(stdin.getBytes(UTF_8));
            } catch (IOException e) {
                // the command exited without reading stdin; its exit code and stderr say why
            }
            String output = new String(process.getInputStream().readAllBytes(), UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) throw new RuntimeException("Command failed with exit code " + exitCode + ": " + String.join(" ", command) + "\n" + output + Files.readString(errors));
            return output;
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    private ProcessBuilder processBuilder(String... command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(dockerDir);
        processBuilder.environment().put("PROJECT_ROOT", projectDir.getAbsolutePath());
        return processBuilder;
    }

    private void waitForTrino() throws Exception {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < TimeUnit.SECONDS.toMillis(TRINO_MAX_WAIT_SECONDS)) {
            if (isTrinoReady()) return;
            TimeUnit.SECONDS.sleep(1);
        }
        throw new RuntimeException("Trino did not become ready within " + TRINO_MAX_WAIT_SECONDS + " seconds");
    }

    private static boolean isTrinoReady() {
        try (Connection connection = DriverManager.getConnection(TRINO_JDBC_URL, TRINO_USER, null);
             Statement statement = connection.createStatement()) {
            Set<String> ready = new HashSet<>();
            try (ResultSet catalogs = statement.executeQuery("SHOW CATALOGS")) {
                while (catalogs.next()) ready.add(catalogs.getString(1));
            }
            return ready.containsAll(CATALOGS);
        } catch (SQLException e) {
            return false;
        }
    }

}