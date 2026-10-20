// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.github.robfromboulder.viewzoo.storage.ViewZooStorageClient;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableProperties;
import io.trino.spi.connector.ConnectorTableVersion;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.ViewNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;

public class ViewZooMetadata implements ConnectorMetadata {

    // define maximum length for schema and table names
    // conservative limits ensure filesystem compatibility
    // "schema.table.json" = 100 + 1 + 100 + 5 = 206 < 255
    public static final int MAX_SCHEMA_NAME_LENGTH = 100;
    public static final int MAX_TABLE_NAME_LENGTH = 100;

    @Inject
    public ViewZooMetadata(ViewZooStorageClient storageClient) {
        this.storageClient = storageClient;
        this.views = storageClient.getViews();
    }

    private final ViewZooStorageClient storageClient;
    // updated only after storage succeeds, so a storage failure leaves the cache unchanged
    private final Map<SchemaTableName, ConnectorViewDefinition> views;

    @Override
    public synchronized void createView(ConnectorSession session, SchemaTableName stn, ConnectorViewDefinition definition, Map<String, Object> viewProperties, boolean replace) {
        validateName(stn);
        if (!replace && views.containsKey(stn)) throw new TrinoException(ALREADY_EXISTS, "View already exists: " + stn);
        storageClient.createView(stn.getSchemaName(), stn.getTableName(), definition);
        views.put(stn, definition);
    }

    @Override
    public synchronized void dropView(ConnectorSession session, SchemaTableName stn) {
        if (!views.containsKey(stn)) throw new ViewNotFoundException(stn);
        storageClient.dropView(stn.getSchemaName(), stn.getTableName());
        views.remove(stn);
    }

    @Override
    public synchronized void renameView(ConnectorSession session, SchemaTableName source, SchemaTableName target) {
        validateName(target);
        ConnectorViewDefinition definition = views.get(source);
        if (definition == null) throw new ViewNotFoundException(source);
        if (views.containsKey(target)) throw new TrinoException(ALREADY_EXISTS, "View already exists: " + target);
        try {
            storageClient.renameView(source.getSchemaName(), source.getTableName(), target.getSchemaName(), target.getTableName());
        } catch (ViewNotFoundException e) {
            // storage lost the view outside Trino; evict it so it stops being listed
            views.remove(source);
            throw e;
        }
        views.remove(source);
        views.put(target, definition);
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle table) {
        return null;
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle) {
        return null;
    }

    @Override
    public ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName, Optional<ConnectorTableVersion> startVersion, Optional<ConnectorTableVersion> endVersion) {
        return null;
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table) {
        return null;
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle tableHandle) {
        return new ConnectorTableProperties();
    }

    @Override
    public synchronized Optional<ConnectorViewDefinition> getView(ConnectorSession session, SchemaTableName viewName) {
        return Optional.ofNullable(views.get(viewName));
    }

    @Override
    public synchronized Map<SchemaTableName, ConnectorViewDefinition> getViews(ConnectorSession session, Optional<String> schemaName) {
        SchemaTablePrefix prefix = schemaName.map(SchemaTablePrefix::new).orElseGet(SchemaTablePrefix::new);
        return ImmutableMap.copyOf(Maps.filterKeys(views, prefix::matches));
    }

    @Override
    public synchronized List<String> listSchemaNames(ConnectorSession session) {
        return views.keySet().stream().map(SchemaTableName::getSchemaName).collect(toImmutableList());
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName) {
        return listViews(session, schemaName);
    }

    @Override
    public synchronized List<SchemaTableName> listViews(ConnectorSession session, Optional<String> schemaName) {
        return views.keySet().stream().filter(viewName -> schemaName.map(viewName.getSchemaName()::equals).orElse(true)).collect(toImmutableList());
    }

    private static void validateName(SchemaTableName stn) {
        String schema = stn.getSchemaName();
        String table = stn.getTableName();
        if (schema.contains(".")) {
            throw new TrinoException(INVALID_ARGUMENTS, "Invalid schema name: " + schema);
        } else if (table.contains(".")) {
            throw new TrinoException(INVALID_ARGUMENTS, "Invalid table name: " + table);
        } else if (schema.length() > MAX_SCHEMA_NAME_LENGTH) {
            throw new TrinoException(INVALID_ARGUMENTS, "Schema name exceeds maximum length of " + MAX_SCHEMA_NAME_LENGTH + ": " + schema);
        } else if (table.length() > MAX_TABLE_NAME_LENGTH) {
            throw new TrinoException(INVALID_ARGUMENTS, "Table name exceeds maximum length of " + MAX_TABLE_NAME_LENGTH + ": " + table);
        }
    }

}
