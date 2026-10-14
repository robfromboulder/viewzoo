// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.ViewNotFoundException;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
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
        if (!dir.isDirectory()) throw new TrinoException(CONFIGURATION_INVALID, "Directory does not exist: " + dir);
        if (!dir.canRead()) throw new TrinoException(CONFIGURATION_INVALID, "Directory is not readable: " + dir);
        if (!dir.canWrite()) throw new TrinoException(CONFIGURATION_INVALID, "Directory is not writable: " + dir);
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
        Path path = viewPath(schema, table);
        try {
            Files.writeString(path, mapper.writeValueAsString(definition));
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to write file: " + path);
        }
    }

    @Override
    public void dropView(String schema, String table) {
        Path path = viewPath(schema, table);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to delete file: " + path);
        }
    }

    @Override
    public void renameView(String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
        Path source = viewPath(sourceSchema, sourceTable);
        Path target = viewPath(targetSchema, targetTable);
        // link(2) atomically refuses an existing target, where Files.move only checks before rename(2) replaces it
        try {
            Files.createLink(target, source);
        } catch (FileAlreadyExistsException e) {
            throw new TrinoException(ALREADY_EXISTS, "View already exists: " + new SchemaTableName(targetSchema, targetTable), e);
        } catch (NoSuchFileException e) {
            throw new ViewNotFoundException(new SchemaTableName(sourceSchema, sourceTable));
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to link file: " + source + " to " + target + ": " + e.getMessage(), e);
        }
        try {
            Files.delete(source);
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to delete file: " + source + ": " + e.getMessage(), e);
        }
    }

    private Path viewPath(String schema, String table) {
        return dir.toPath().resolve(schema + "." + table + ".json");
    }

}
