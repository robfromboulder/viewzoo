// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.github.robfromboulder.viewzoo.storage.ViewZooStorageClient;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.ViewNotFoundException;
import io.trino.spi.type.TypeId;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TestViewZooMetadata {

    private static final SchemaTableName SOURCE = new SchemaTableName("s", "source");
    private static final SchemaTableName TARGET = new SchemaTableName("t", "target");
    private static final ConnectorViewDefinition DEFINITION = new ConnectorViewDefinition("SELECT 1 AS x", Optional.empty(), Optional.empty(), List.of(new ViewColumn("x", TypeId.of("integer"), Optional.empty())), Optional.empty(), Optional.empty(), false, List.of());

    private FakeStorageClient storage;
    private ViewZooMetadata metadata;

    @BeforeMethod
    public void setUp() {
        storage = new FakeStorageClient();
        storage.views.put(SOURCE, DEFINITION);
        metadata = new ViewZooMetadata(storage);
    }

    @Test
    public void testRenameView() {
        metadata.renameView(null, SOURCE, TARGET);
        assertEquals(storage.renames, List.of("s.source->t.target"));
        assertEquals(metadata.getView(null, TARGET), Optional.of(DEFINITION));
        assertEquals(metadata.getView(null, SOURCE), Optional.empty());
    }

    @Test
    public void testRenameMissingView() {
        assertThrows(ViewNotFoundException.class, () -> metadata.renameView(null, new SchemaTableName("s", "missing"), TARGET));
        assertTrue(storage.renames.isEmpty());
    }

    @Test
    public void testRenameOntoExistingView() {
        metadata.createView(null, TARGET, DEFINITION, Map.of(), false);
        assertErrorCode(() -> metadata.renameView(null, SOURCE, TARGET), ALREADY_EXISTS.toErrorCode().getCode());
        assertTrue(storage.renames.isEmpty());
        assertTrue(metadata.getView(null, SOURCE).isPresent());
    }

    @Test
    public void testRenameToInvalidName() {
        assertErrorCode(() -> metadata.renameView(null, SOURCE, new SchemaTableName("s", "invalid.table")), INVALID_ARGUMENTS.toErrorCode().getCode());
        assertErrorCode(() -> metadata.renameView(null, SOURCE, new SchemaTableName("s", "x".repeat(101))), INVALID_ARGUMENTS.toErrorCode().getCode());
        assertTrue(storage.renames.isEmpty());
    }

    @Test
    public void testRenameStorageFailureLeavesCacheUnchanged() {
        storage.failRename = true;
        assertErrorCode(() -> metadata.renameView(null, SOURCE, TARGET), GENERIC_INTERNAL_ERROR.toErrorCode().getCode());
        assertEquals(metadata.getView(null, SOURCE), Optional.of(DEFINITION));
        assertEquals(metadata.getView(null, TARGET), Optional.empty());
    }

    private static void assertErrorCode(Runnable runnable, int code) {
        TrinoException e = expectThrows(TrinoException.class, runnable::run);
        assertEquals(e.getErrorCode().getCode(), code);
    }

    private static class FakeStorageClient implements ViewZooStorageClient {

        private final Map<SchemaTableName, ConnectorViewDefinition> views = new HashMap<>();
        private final List<String> renames = new ArrayList<>();
        private boolean failRename;

        @Override
        public Map<SchemaTableName, ConnectorViewDefinition> getViews() {
            return new HashMap<>(views);
        }

        @Override
        public void createView(String schema, String table, ConnectorViewDefinition definition) {}

        @Override
        public void dropView(String schema, String table) {}

        @Override
        public void renameView(String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
            if (failRename) throw new TrinoException(GENERIC_INTERNAL_ERROR, "Simulated storage failure");
            renames.add(sourceSchema + "." + sourceTable + "->" + targetSchema + "." + targetTable);
        }

    }

}
