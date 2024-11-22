// © 2024 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
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

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.READ_ONLY_VIOLATION;
import static java.util.Objects.requireNonNull;

public class ViewZooMetadata implements ConnectorMetadata {

    public static final List<String> SCHEMA_NAMES = ImmutableList.of("example");

    @Inject
    public ViewZooMetadata(ViewZooConfig config) {
        this.config = requireNonNull(config, "config is null");
        if (config.getViewsDir() != null) buildViews();
    }

    private final ViewZooConfig config;
    private final Map<SchemaTableName, ConnectorViewDefinition> views = new HashMap<>();

    private synchronized void buildViews() {
        File dir = new File(config.getViewsDir());
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new TrinoException(CONFIGURATION_INVALID, "Unable to access directory: " + config.getViewsDir());

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new Jdk8Module());

        for (File f : Stream.of(requireNonNull(dir.listFiles())).filter(f -> !f.isHidden() && f.getName().endsWith(".json")).toList()) {
            try {
                ConnectorViewDefinition def = mapper.readValue(f, ConnectorViewDefinition.class);
                String filename = f.getName();
                String[] name_pieces = filename.split("\\.");
                if ((name_pieces.length == 3) && SCHEMA_NAMES.contains(name_pieces[0]) && name_pieces[2].equals("json"))
                    views.put(new SchemaTableName(name_pieces[0], name_pieces[1]), def);
            } catch (IOException e) {
                throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to read file: " + f);
            }
        }
    }

    @Override
    public synchronized void createView(ConnectorSession session, SchemaTableName viewName, ConnectorViewDefinition definition, Map<String, Object> viewProperties, boolean replace) {
        if (config.getViewsDir() == null)
            throw new TrinoException(CONFIGURATION_INVALID, "Not configured for persistent views");

        String schema = viewName.getSchemaName();
        if (!SCHEMA_NAMES.contains(schema)) {
            throw new TrinoException(READ_ONLY_VIOLATION, "Schema is not writeable: " + viewName);
        } else if (replace) {
            views.put(viewName, definition);
        } else if (views.putIfAbsent(viewName, definition) != null) {
            throw new TrinoException(ALREADY_EXISTS, "View already exists: " + viewName);
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        try {
            String json = mapper.writeValueAsString(definition);
            File f = new File(new File(config.getViewsDir()), schema + "." + viewName.getTableName() + ".json");
            Files.write(Paths.get(f.toURI()), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public synchronized void dropView(ConnectorSession session, SchemaTableName viewName) {
        if (config.getViewsDir() == null)
            throw new TrinoException(CONFIGURATION_INVALID, "Not configured for persistent views");

        String schema = viewName.getSchemaName();
        if (!SCHEMA_NAMES.contains(schema)) {
            throw new TrinoException(READ_ONLY_VIOLATION, "Schema is not writeable: " + viewName);
        } else if (views.remove(viewName) == null) {
            throw new ViewNotFoundException(viewName);
        }

        try {
            File f = new File(new File(config.getViewsDir()), schema + "." + viewName.getTableName() + ".json");
            Files.deleteIfExists(Paths.get(f.toURI()));
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, e.getMessage());
        }
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
    public List<String> listSchemaNames(ConnectorSession session) {
        return SCHEMA_NAMES;
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
