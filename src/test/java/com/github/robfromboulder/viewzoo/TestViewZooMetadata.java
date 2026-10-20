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
import java.util.Set;

import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
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
    public void testCreateView() {
        metadata.createView(null, TARGET, DEFINITION, Map.of(), false);
        assertEquals(storage.creates, List.of("t.target"));
        assertEquals(metadata.getView(null, TARGET), Optional.of(DEFINITION));
    }

    @Test
    public void testCreateExistingView() {
        assertErrorCode(() -> metadata.createView(null, SOURCE, DEFINITION, Map.of(), false), ALREADY_EXISTS.toErrorCode().getCode());
        assertTrue(storage.creates.isEmpty());
    }

    @Test
    public void testListViews() {
        metadata.createView(null, TARGET, DEFINITION, Map.of(), false);
        assertEquals(Set.copyOf(metadata.listViews(null, Optional.empty())), Set.of(SOURCE, TARGET));
        assertEquals(metadata.listViews(null, Optional.of("s")), List.of(SOURCE));
        assertEquals(metadata.listViews(null, Optional.of("missing")), List.of());
        assertEquals(Set.copyOf(metadata.listTables(null, Optional.empty())), Set.of(SOURCE, TARGET));
        assertEquals(metadata.listTables(null, Optional.of("t")), List.of(TARGET));
    }

    // the connector holds no tables, so lookups return the SPI's not-found null and properties are empty
    @Test
    public void testTableMethods() {
        assertNull(metadata.getTableHandle(null, SOURCE, Optional.empty(), Optional.empty()));
        assertNull(metadata.getTableMetadata(null, null));
        assertNull(metadata.getColumnHandles(null, null));
        assertNull(metadata.getColumnMetadata(null, null, null));
        assertNotNull(metadata.getTableProperties(null, null));
    }

    @Test
    public void testReplacePassedToStorage() {
        metadata.createView(null, SOURCE, DEFINITION, Map.of(), true);
        assertEquals(storage.creates, List.of("s.source replace"));
    }

    // a view added to storage after the cache loaded, e.g. by another coordinator
    @Test
    public void testCreateRefusedByStorage() {
        storage.views.put(TARGET, DEFINITION);
        assertErrorCode(() -> metadata.createView(null, TARGET, DEFINITION, Map.of(), false), ALREADY_EXISTS.toErrorCode().getCode());
        assertEquals(metadata.getView(null, TARGET), Optional.empty());
    }

    @Test
    public void testCreateInvalidName() {
        assertErrorCode(() -> metadata.createView(null, new SchemaTableName("s", "invalid.table"), DEFINITION, Map.of(), false), INVALID_ARGUMENTS.toErrorCode().getCode());
        assertTrue(storage.creates.isEmpty());
    }

    @Test
    public void testDropView() {
        metadata.dropView(null, SOURCE);
        assertEquals(storage.drops, List.of("s.source"));
        assertEquals(metadata.getView(null, SOURCE), Optional.empty());
    }

    @Test
    public void testDropMissingView() {
        assertThrows(ViewNotFoundException.class, () -> metadata.dropView(null, new SchemaTableName("s", "missing")));
        assertTrue(storage.drops.isEmpty());
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

    @Test
    public void testRenameEvictsViewMissingFromStorage() {
        storage.missingOnRename = true;
        assertThrows(ViewNotFoundException.class, () -> metadata.renameView(null, SOURCE, TARGET));
        assertEquals(metadata.getView(null, SOURCE), Optional.empty());
        assertEquals(metadata.getView(null, TARGET), Optional.empty());
    }

    @Test
    public void testCreateStorageFailureLeavesCacheUnchanged() {
        storage.failWrites = true;
        ConnectorViewDefinition replacement = new ConnectorViewDefinition("SELECT 2 AS x", Optional.empty(), Optional.empty(), DEFINITION.getColumns(), Optional.empty(), Optional.empty(), false, List.of());
        assertErrorCode(() -> metadata.createView(null, TARGET, DEFINITION, Map.of(), false), GENERIC_INTERNAL_ERROR.toErrorCode().getCode());
        assertErrorCode(() -> metadata.createView(null, SOURCE, replacement, Map.of(), true), GENERIC_INTERNAL_ERROR.toErrorCode().getCode());
        assertEquals(metadata.getView(null, TARGET), Optional.empty());
        assertEquals(metadata.getView(null, SOURCE), Optional.of(DEFINITION));
    }

    @Test
    public void testDropStorageFailureLeavesCacheUnchanged() {
        storage.failWrites = true;
        assertErrorCode(() -> metadata.dropView(null, SOURCE), GENERIC_INTERNAL_ERROR.toErrorCode().getCode());
        assertEquals(metadata.getView(null, SOURCE), Optional.of(DEFINITION));
    }

    private static void assertErrorCode(Runnable runnable, int code) {
        TrinoException e = expectThrows(TrinoException.class, runnable::run);
        assertEquals(e.getErrorCode().getCode(), code);
    }

    private static class FakeStorageClient implements ViewZooStorageClient {

        private final Map<SchemaTableName, ConnectorViewDefinition> views = new HashMap<>();
        private final List<String> creates = new ArrayList<>();
        private final List<String> drops = new ArrayList<>();
        private final List<String> renames = new ArrayList<>();
        private boolean failRename;
        private boolean missingOnRename;
        private boolean failWrites;

        @Override
        public Map<SchemaTableName, ConnectorViewDefinition> getViews() {
            return new HashMap<>(views);
        }

        @Override
        public void createView(String schema, String table, ConnectorViewDefinition definition, boolean replace) {
            if (failWrites) throw new TrinoException(GENERIC_INTERNAL_ERROR, "Simulated storage failure");
            SchemaTableName stn = new SchemaTableName(schema, table);
            if (!replace && views.containsKey(stn)) throw new TrinoException(ALREADY_EXISTS, "View already exists: " + stn);
            views.put(stn, definition);
            creates.add(stn + (replace ? " replace" : ""));
        }

        @Override
        public void dropView(String schema, String table) {
            if (failWrites) throw new TrinoException(GENERIC_INTERNAL_ERROR, "Simulated storage failure");
            views.remove(new SchemaTableName(schema, table));
            drops.add(schema + "." + table);
        }

        @Override
        public void renameView(String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
            if (failRename) throw new TrinoException(GENERIC_INTERNAL_ERROR, "Simulated storage failure");
            if (missingOnRename) throw new ViewNotFoundException(new SchemaTableName(sourceSchema, sourceTable));
            ConnectorViewDefinition definition = views.remove(new SchemaTableName(sourceSchema, sourceTable));
            if (definition != null) views.put(new SchemaTableName(targetSchema, targetTable), definition);
            renames.add(sourceSchema + "." + sourceTable + "->" + targetSchema + "." + targetTable);
        }

    }

}
