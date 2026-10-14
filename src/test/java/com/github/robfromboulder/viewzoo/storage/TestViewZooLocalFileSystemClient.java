// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ViewNotFoundException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.expectThrows;

public class TestViewZooLocalFileSystemClient {

    private Path dir;
    private ViewZooLocalFileSystemClient client;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("viewzoo");
        client = new ViewZooLocalFileSystemClient(new ViewZooFilesystemConfig().setDir(dir.toString()), new ObjectMapper());
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
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

}
