// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.github.robfromboulder.viewzoo.storage.ViewZooJdbcClient;
import com.github.robfromboulder.viewzoo.storage.ViewZooLocalFileSystemClient;
import com.github.robfromboulder.viewzoo.storage.ViewZooStorageClient;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
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

import com.google.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static java.util.Objects.requireNonNull;

public class ViewZooMetadata implements ConnectorMetadata {

    @Inject
    public ViewZooMetadata(ViewZooConfig config) {
        this.config = requireNonNull(config, "config is null");
        String storageType = config.getStorageType();

        if (storageType.equals("filesystem")) {
            if (config.getDir() != null) {
                storageClient = new ViewZooLocalFileSystemClient(config.getDir());
            }
            else {
                throw new TrinoException(CONFIGURATION_INVALID, "Configuration viewzoo.dir not set");
            }
        }
        else if (storageType.equals("jdbc")) {
            storageClient = new ViewZooJdbcClient(config.getJdbcUrl(), config.getJdbcUser(), config.getJdbcPassword());
        }
        else {
            throw new TrinoException(CONFIGURATION_INVALID, "Unsupported storage type: " + storageType);
        }

        buildViews();
    }

    private final ViewZooConfig config;
    private final ViewZooStorageClient storageClient;
    private Map<SchemaTableName, ConnectorViewDefinition> views;

    private synchronized void buildViews() {
        views = storageClient.getViews();
    }

    @Override
    public synchronized void createView(ConnectorSession session, SchemaTableName stn, ConnectorViewDefinition definition, Map<String, Object> viewProperties, boolean replace) {
        String schema = stn.getSchemaName();
        String table = stn.getTableName();
        if (schema.contains(".")) {
            throw new TrinoException(INVALID_ARGUMENTS, "Invalid schema name: " + schema);
        } else if (table.contains(".")) {
            throw new TrinoException(INVALID_ARGUMENTS, "Invalid table name: " + table);
        } else if (replace) {
            views.put(stn, definition);
        } else if (views.putIfAbsent(stn, definition) != null) {
            throw new TrinoException(ALREADY_EXISTS, "View already exists: " + stn);
        }

        storageClient.createView(schema, table, definition);
    }

    @Override
    public synchronized void dropView(ConnectorSession session, SchemaTableName stn) {
        if (config.getDir() == null)
            throw new TrinoException(CONFIGURATION_INVALID, "Not configured for persistent views");

        if (views.remove(stn) == null) throw new ViewNotFoundException(stn);

        String schema = stn.getSchemaName();
        String table = stn.getTableName();
        storageClient.dropView(schema, table);
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

}
