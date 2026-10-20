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
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
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
        client.createView(name, "t", DEFINITION, false);
        client.createView("s", name, DEFINITION, false);
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
        assertRejected(() -> client.createView("a/b", "t", DEFINITION, false));
        assertRejected(() -> client.createView("s", "a/b", DEFINITION, false));
        assertEquals(files(), Set.of());
    }

    @Test
    public void testSlashInNameRejectedWithExistingSubdirectory() throws IOException {
        Files.createDirectory(dir.resolve("a"));
        assertRejected(() -> client.createView("a/b", "t", DEFINITION, false));
        assertEquals(files(), Set.of());
    }

    @Test
    public void testAbsoluteSchemaRejected() throws IOException {
        Path other = Files.createDirectory(root.resolve("other"));
        assertRejected(() -> client.createView(other + "/x", "t", DEFINITION, false));
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
        assertRejected(() -> client.createView("cafe\u0301", "t", DEFINITION, false));
        assertRejected(() -> client.createView("s", "cafe\u0301", DEFINITION, false));
        client.createView("s", "source", DEFINITION, false);
        assertRejected(() -> client.renameView("s", "source", "s", "cafe\u0301"));
        assertEquals(files(), Set.of("views/s.source.json"));
    }

    @Test
    public void testNulInNameRejected() throws IOException {
        assertRejected(() -> client.createView("a\u0000b", "t", DEFINITION, false));
        assertRejected(() -> client.createView("s", "a\u0000b", DEFINITION, false));
        assertEquals(files(), Set.of());
    }

    @Test
    public void testCaseInsensitiveCollisionRejected() throws IOException {
        Files.writeString(dir.resolve("S.T.json"), "other");
        if (!Files.exists(dir.resolve("s.t.json"))) throw new SkipException("filesystem is case-sensitive");
        TrinoException e = expectThrows(TrinoException.class, () -> client.createView("s", "t", DEFINITION, false));
        assertEquals(e.getErrorCode(), ALREADY_EXISTS.toErrorCode());
        client.createView("s", "source", DEFINITION, false);
        e = expectThrows(TrinoException.class, () -> client.renameView("s", "source", "s", "t"));
        assertEquals(e.getErrorCode(), ALREADY_EXISTS.toErrorCode());
        assertEquals(e.getMessage(), "View conflicts with an existing view file in filesystem storage: s.t");
        assertEquals(Files.readString(dir.resolve("S.T.json")), "other");
        assertEquals(files(), Set.of("views/S.T.json", "views/s.source.json"));
    }

    @Test
    public void testReplaceView() throws IOException {
        Files.writeString(dir.resolve("s.t.json"), "old");
        client.createView("s", "t", DEFINITION, true);
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName("s", "t")));
        assertEquals(files(), Set.of("views/s.t.json"));
    }

    @Test
    public void testCreateDoesNotOverwriteExistingFile() throws IOException {
        Files.writeString(dir.resolve("s.t.json"), "other");
        TrinoException e = expectThrows(TrinoException.class, () -> client.createView("s", "t", DEFINITION, false));
        assertEquals(e.getErrorCode(), ALREADY_EXISTS.toErrorCode());
        assertEquals(Files.readString(dir.resolve("s.t.json")), "other");
        assertEquals(files(), Set.of("views/s.t.json"));
    }

    // a write that fails partway must leave the old file whole, so the write can't go to the view's own file
    @Test
    public void testReplaceDoesNotWriteInPlace() throws Throwable {
        Files.writeString(dir.resolve("s.t.json"), "old");
        withDirPermissions("r-xr-xr-x", () -> expectThrows(TrinoException.class, () -> client.createView("s", "t", DEFINITION, true)));
        assertEquals(Files.readString(dir.resolve("s.t.json")), "old");
    }

    @Test
    public void testCreateSucceedsWhenTempDeleteFails() {
        client = newClient(path -> path.getFileName().toString().startsWith(".viewzoo-"));
        client.createView("s", "t", DEFINITION, false);
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName("s", "t")));
    }

    @Test
    public void testRenameLinkFailureKeepsSource() throws Throwable {
        Files.writeString(dir.resolve("s.source.json"), "source");
        withDirPermissions("r-xr-xr-x", () -> {
            TrinoException e = expectThrows(TrinoException.class, () -> client.renameView("s", "source", "t", "target"));
            assertTrue(e.getMessage().startsWith("Failed to link file: "), e.getMessage());
        });
        assertEquals(files(), Set.of("views/s.source.json"));
    }

    // a failed source delete must not leave the view stored under both names
    @Test
    public void testRenameRollsBackWhenSourceDeleteFails() throws IOException {
        Files.writeString(dir.resolve("s.source.json"), "source");
        client = newClient(path -> path.getFileName().toString().equals("s.source.json"));
        assertKeepsCause(expectThrows(TrinoException.class, () -> client.renameView("s", "source", "t", "target")));
        assertEquals(files(), Set.of("views/s.source.json"));
    }

    @Test
    public void testRenameRollbackFailureIsSuppressed() throws IOException {
        Files.writeString(dir.resolve("s.source.json"), "source");
        client = newClient(path -> true);
        TrinoException e = expectThrows(TrinoException.class, () -> client.renameView("s", "source", "t", "target"));
        assertEquals(e.getSuppressed().length, 1);
        assertEquals(files(), Set.of("views/s.source.json", "views/t.target.json"));
    }

    // an execute-only directory resolves the target but can't be listed
    @Test
    public void testConflictCheckListingFailure() throws Throwable {
        Files.writeString(dir.resolve("s.t.json"), "old");
        Files.writeString(dir.resolve("s.source.json"), "source");
        withDirPermissions("--x--x--x", () -> {
            for (ThrowingRunnable write : List.<ThrowingRunnable>of(() -> client.createView("s", "t", DEFINITION, true), () -> client.renameView("s", "source", "s", "t"))) {
                TrinoException e = expectThrows(TrinoException.class, write);
                assertTrue(e.getMessage().startsWith("Failed to list directory: "), e.getMessage());
            }
        });
        assertEquals(Files.readString(dir.resolve("s.t.json")), "old");
        assertEquals(files(), Set.of("views/s.t.json", "views/s.source.json"));
    }

    @DataProvider
    public Object[][] unusableDirectories() {
        return new Object[][]{{"-wx-wx-wx", "Directory is not readable: "}, {"r-xr-xr-x", "Directory is not writable: "}};
    }

    @Test(dataProvider = "unusableDirectories")
    public void testUnusableDirectoryFailsLoad(String permissions, String message) throws Throwable {
        withDirPermissions(permissions, () -> {
            TrinoException e = expectThrows(TrinoException.class, () -> client.getViews());
            assertEquals(e.getErrorCode(), CONFIGURATION_INVALID.toErrorCode());
            assertEquals(e.getMessage(), message + dir);
        });
    }

    @Test
    public void testTempFileNotLoaded() throws IOException {
        Files.writeString(dir.resolve(".viewzoo-0.tmp"), ViewZooStorageClientProvider.createObjectMapper().writeValueAsString(DEFINITION));
        assertEquals(newClient().getViews(), Map.of());
    }

    @Test
    public void testMalformedFileNamesSkipped() throws IOException {
        for (String name : List.of("s..json", "a.b.c.json", "x.json")) {
            Files.writeString(dir.resolve(name), "not json");
        }
        // uppercase names load under a lowercased name that drop and replace would miss
        Files.writeString(dir.resolve("S.U.json"), ViewZooStorageClientProvider.createObjectMapper().writeValueAsString(DEFINITION));
        client.createView("s", "t", DEFINITION, false);
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName("s", "t")));
    }

    @DataProvider
    public Object[][] unreadableContents() {
        return new Object[][]{{"not json"}, {""}, {"{\"originalSql\":\"SELECT"}, {"{}"}, {"[]"}, {"null"}};
    }

    @Test(dataProvider = "unreadableContents")
    public void testUnreadableFileSkipped(String contents) throws IOException {
        Files.writeString(dir.resolve("s.bad.json"), contents);
        client.createView("s", "t", DEFINITION, false);
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName("s", "t")));
    }

    @Test
    public void testDirectoryWithViewFileNameSkipped() throws IOException {
        Files.createDirectory(dir.resolve("s.bad.json"));
        client.createView("s", "t", DEFINITION, false);
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName("s", "t")));
    }

    @Test
    public void testMissingDirectoryFailsLoad() throws IOException {
        Files.delete(dir);
        TrinoException e = expectThrows(TrinoException.class, () -> client.getViews());
        assertEquals(e.getErrorCode(), CONFIGURATION_INVALID.toErrorCode());
    }

    @Test
    public void testCreateFailureKeepsCause() throws IOException {
        Files.writeString(Files.createDirectory(dir.resolve("s.t.json")).resolve("child"), "child");
        assertKeepsCause(expectThrows(TrinoException.class, () -> client.createView("s", "t", DEFINITION, true)));
        assertEquals(files(), Set.of("views/s.t.json/child"));
    }

    @Test
    public void testDropFailureKeepsCause() throws IOException {
        Files.writeString(Files.createDirectory(dir.resolve("s.t.json")).resolve("child"), "child");
        assertKeepsCause(expectThrows(TrinoException.class, () -> client.dropView("s", "t")));
    }

    @Test
    public void testFileNameByteLimit() throws IOException {
        // "s." + table + ".json" at exactly 255 bytes is the longest filename Linux allows
        client.createView("s", "x".repeat(248), DEFINITION, false);
        assertEquals(newClient().getViews().keySet(), Set.of(new SchemaTableName("s", "x".repeat(248))));
        // 84 three-byte characters make a 259-byte filename, which macOS accepts but Linux does not
        assertRejected(() -> client.createView("s", "日".repeat(84), DEFINITION, false));
        assertRejected(() -> client.createView("日".repeat(84), "t", DEFINITION, false));
        assertEquals(files(), Set.of("views/s." + "x".repeat(248) + ".json"));
    }

    @Test
    public void testRenameToRejectedNameKeepsSource() throws IOException {
        client.createView("s", "source", DEFINITION, false);
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

    private static void assertKeepsCause(TrinoException e) {
        assertTrue(e.getCause() instanceof IOException, "Expected an IOException cause: " + e.getCause());
        assertTrue(e.getMessage().endsWith(": " + e.getCause().getMessage()), "Expected the cause's message: " + e.getMessage());
    }

    private ViewZooLocalFileSystemClient newClient() {
        return newClient(path -> false);
    }

    private ViewZooLocalFileSystemClient newClient(Predicate<Path> failDelete) {
        return new ViewZooLocalFileSystemClient(new ViewZooFilesystemConfig().setDir(dir.toString()), ViewZooStorageClientProvider.createObjectMapper()) {
            @Override
            void deleteFile(Path path) throws IOException {
                if (failDelete.test(path)) throw new IOException("Simulated delete failure");
                super.deleteFile(path);
            }
        };
    }

    // skips rather than passes vacuously when run as a user who ignores permissions, such as root
    private void withDirPermissions(String permissions, ThrowingRunnable runnable) throws Throwable {
        Set<PosixFilePermission> granted = PosixFilePermissions.fromString(permissions);
        Files.setPosixFilePermissions(dir, granted);
        try {
            if (Files.isReadable(dir) != granted.contains(OWNER_READ) || Files.isWritable(dir) != granted.contains(OWNER_WRITE)) {
                throw new SkipException("running as a user who ignores permissions");
            }
            runnable.run();
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private Set<String> files() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).collect(toSet());
        }
    }

}
