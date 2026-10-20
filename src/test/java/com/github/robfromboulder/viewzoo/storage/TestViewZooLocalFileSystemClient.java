// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.ViewNotFoundException;
import io.trino.spi.type.TypeId;
import org.testng.Assert.ThrowingRunnable;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static java.util.stream.Collectors.toSet;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TestViewZooLocalFileSystemClient {

    private static final ConnectorViewDefinition DEFINITION = new ConnectorViewDefinition("SELECT 1 AS x", Optional.empty(), Optional.empty(), List.of(new ViewColumn("x", TypeId.of("integer"), Optional.empty())), Optional.empty(), Optional.empty(), false, List.of());

    // views live one level below root so paths that escape the views directory stay inside the test's temp space
    private Path root;
    private Path dir;
    private ViewZooLocalFileSystemClient client;

    @BeforeMethod
    public void setUp() throws IOException {
        root = Files.createTempDirectory("viewzoo");
        dir = Files.createDirectory(root.resolve("views"));
        client = newClient();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void testRenameView() throws IOException {
        Files.writeString(dir.resolve("s.source.json"), "source");
        client.renameView("s", "source", "t", "target");
        assertFalse(Files.exists(dir.resolve("s.source.json")));
        assertEquals(Files.readString(dir.resolve("t.target.json")), "source");
    }

    @Test
    public void testRenameDoesNotOverwriteExistingFile() throws IOException {
        Files.writeString(dir.resolve("s.source.json"), "source");
        Files.writeString(dir.resolve("t.target.json"), "target");
        TrinoException e = expectThrows(TrinoException.class, () -> client.renameView("s", "source", "t", "target"));
        assertEquals(e.getErrorCode(), ALREADY_EXISTS.toErrorCode());
        assertEquals(Files.readString(dir.resolve("s.source.json")), "source");
        assertEquals(Files.readString(dir.resolve("t.target.json")), "target");
    }

    @Test
    public void testRenameMissingSource() {
        expectThrows(ViewNotFoundException.class, () -> client.renameView("s", "source", "t", "target"));
        assertFalse(Files.exists(dir.resolve("t.target.json")));
    }

    @DataProvider
    public Object[][] portableNames() {
        return new Object[][]{{"a b"}, {"a-b_c"}, {"a\\b"}, {"a:b"}, {"a*b?"}, {"a<b>|"}, {"a\"b"}, {"a'b%"}, {"a\tb"}, {"a\nb"}, {"café"}, {"日本"}, {"🐧"}, {" lead"}, {"trail "}};
    }

    @Test(dataProvider = "portableNames")
    public void testNameRoundTrips(String name) {
        client.createView(name, "t", DEFINITION);
        client.createView("s", name, DEFINITION);
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName(name, "t"), new SchemaTableName("s", name)));

        client.renameView(name, "t", name, "u");
        client.renameView("s", name, "s", name + "2");
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName(name, "u"), new SchemaTableName("s", name + "2")));

        client.dropView(name, "u");
        client.dropView("s", name + "2");
        assertTrue(newClient().getViews().isEmpty());
    }

    @Test
    public void testSlashInNameRejected() throws IOException {
        assertRejected(() -> client.createView("a/b", "t", DEFINITION));
        assertRejected(() -> client.createView("s", "a/b", DEFINITION));
        assertEquals(files(), Set.of());
    }

    @Test
    public void testSlashInNameRejectedWithExistingSubdirectory() throws IOException {
        Files.createDirectory(dir.resolve("a"));
        assertRejected(() -> client.createView("a/b", "t", DEFINITION));
        assertEquals(files(), Set.of());
    }

    @Test
    public void testAbsoluteSchemaRejected() throws IOException {
        Path other = Files.createDirectory(root.resolve("other"));
        assertRejected(() -> client.createView(other + "/x", "t", DEFINITION));
        assertEquals(files(), Set.of());
    }

    // dropView and the rename source skip name validation, so only the path containment check protects them
    @Test
    public void testPathOutsideDirRejected() throws IOException {
        Files.writeString(root.resolve("x.t.json"), "outside");
        assertRejected(() -> client.dropView("../x", "t"));
        assertRejected(() -> client.renameView("../x", "t", "s", "t"));
        assertEquals(files(), Set.of("x.t.json"));
    }

    @Test
    public void testNonNfcNameRejected() throws IOException {
        // "cafe" plus a combining acute accent is the NFD form of "café"
        assertRejected(() -> client.createView("cafe\u0301", "t", DEFINITION));
        assertRejected(() -> client.createView("s", "cafe\u0301", DEFINITION));
        client.createView("s", "source", DEFINITION);
        assertRejected(() -> client.renameView("s", "source", "s", "cafe\u0301"));
        assertEquals(files(), Set.of("views/s.source.json"));
    }

    @Test
    public void testNulInNameRejected() throws IOException {
        assertRejected(() -> client.createView("a\u0000b", "t", DEFINITION));
        assertRejected(() -> client.createView("s", "a\u0000b", DEFINITION));
        assertEquals(files(), Set.of());
    }

    @Test
    public void testUnfoldedNameRejected() throws IOException {
        // final sigma folds to σ, so case-insensitive filesystems store both in one file
        client.createView("s", "σ", DEFINITION);
        assertRejected(() -> client.createView("s", "ς", DEFINITION));
        assertRejected(() -> client.createView("ς", "t", DEFINITION));
        assertRejected(() -> client.renameView("s", "σ", "s", "ς"));
        assertEquals(files(), Set.of("views/s.σ.json"));
    }

    @Test
    public void testUnsupportedFileNameFailsLoad() throws IOException {
        Files.writeString(dir.resolve("s.ς.json"), ViewZooStorageClientProvider.createObjectMapper().writeValueAsString(DEFINITION));
        TrinoException e = expectThrows(TrinoException.class, () -> newClient().getViews());
        assertEquals(e.getErrorCode(), CONFIGURATION_INVALID.toErrorCode());
    }

    @Test
    public void testFileNameByteLimit() throws IOException {
        // "s." + table + ".json" at exactly 255 bytes is the longest filename Linux allows
        client.createView("s", "x".repeat(248), DEFINITION);
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName("s", "x".repeat(248))));
        // 84 three-byte characters make a 259-byte filename, which macOS accepts but Linux does not
        assertRejected(() -> client.createView("s", "日".repeat(84), DEFINITION));
        assertRejected(() -> client.createView("日".repeat(84), "t", DEFINITION));
        assertEquals(files(), Set.of("views/s." + "x".repeat(248) + ".json"));
    }

    @Test
    public void testRenameToRejectedNameKeepsSource() throws IOException {
        client.createView("s", "source", DEFINITION);
        assertRejected(() -> client.renameView("s", "source", "s", "a/b"));
        assertRejected(() -> client.renameView("s", "source", "a/b", "t"));
        assertRejected(() -> client.renameView("s", "source", "s", "a\u0000b"));
        assertRejected(() -> client.renameView("s", "source", "s", "日".repeat(84)));
        assertEquals(files(), Set.of("views/s.source.json"));
    }

    private static void assertRejected(ThrowingRunnable runnable) {
        TrinoException e = expectThrows(TrinoException.class, runnable);
        assertEquals(e.getErrorCode(), INVALID_ARGUMENTS.toErrorCode());
    }

    private ViewZooLocalFileSystemClient newClient() {
        return new ViewZooLocalFileSystemClient(new ViewZooFilesystemConfig().setDir(dir.toString()), ViewZooStorageClientProvider.createObjectMapper());
    }

    private Set<String> files() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).collect(toSet());
        }
    }

}
