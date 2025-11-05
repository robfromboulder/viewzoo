// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.SchemaTableName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.util.Objects.requireNonNull;

public class ViewZooLocalFileSystemClient implements ViewZooStorageClient {

    public ViewZooLocalFileSystemClient(ViewZooFilesystemConfig config, ObjectMapper mapper) {
        this.dir = new File(requireNonNull(config.getDir(), "viewDir is null"));
        this.mapper = mapper;
    }

    private final File dir;
    private final ObjectMapper mapper;

    @Override
    public Map<SchemaTableName, ConnectorViewDefinition> getViews() {
        Map<SchemaTableName, ConnectorViewDefinition> views = new HashMap<>();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new TrinoException(CONFIGURATION_INVALID, "Unable to access directory: " + dir);
        for (File f : Stream.of(requireNonNull(dir.listFiles())).filter(f -> !f.isHidden() && f.getName().endsWith(".json")).toList()) {
            try {
                ConnectorViewDefinition def = mapper.readValue(f, ConnectorViewDefinition.class);
                String filename = f.getName();
                String[] name_pieces = filename.split("\\.");
                if ((name_pieces.length == 3) && name_pieces[2].equals("json"))
                    views.put(new SchemaTableName(name_pieces[0], name_pieces[1]), def);
            } catch (IOException e) {
                throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to read file: " + f);
            }
        }
        return views;
    }

    @Override
    public void createView(String schema, String table, ConnectorViewDefinition definition) {
        try {
            File f = new File(dir, schema + "." + table + ".json");
            Files.writeString(Paths.get(f.toURI()), mapper.writeValueAsString(definition));
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public void dropView(String schema, String table) {
        try {
            File f = new File(dir, schema + "." + table + ".json");
            Files.deleteIfExists(Paths.get(f.toURI()));
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, e.getMessage());
        }
    }

}
