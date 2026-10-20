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
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public class ViewZooLocalFileSystemClient implements ViewZooStorageClient {

    // Linux's limit, enforced on macOS too so a view directory written on either loads on both
    private static final int MAX_FILE_NAME_BYTES = 255;

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
                if ((name_pieces.length == 3) && name_pieces[2].equals("json")) {
                    if (!isStorable(name_pieces[0]) || !isStorable(name_pieces[1]) || filename.getBytes(UTF_8).length > MAX_FILE_NAME_BYTES)
                        throw new TrinoException(CONFIGURATION_INVALID, "View file name not supported by filesystem storage, rename or remove it: " + f);
                    views.put(new SchemaTableName(name_pieces[0], name_pieces[1]), def);
                }
            } catch (IOException e) {
                throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to read file: " + f);
            }
        }
        return views;
    }

    @Override
    public void createView(String schema, String table, ConnectorViewDefinition definition) {
        validateName(schema, table);
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
        validateName(targetSchema, targetTable);
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

    private static void validateName(String schema, String table) {
        if (!isStorable(schema)) {
            throw new TrinoException(INVALID_ARGUMENTS, "Schema name not supported by filesystem storage: " + schema);
        } else if (!isStorable(table)) {
            throw new TrinoException(INVALID_ARGUMENTS, "Table name not supported by filesystem storage: " + table);
        } else if (fileName(schema, table).getBytes(UTF_8).length > MAX_FILE_NAME_BYTES) {
            throw new TrinoException(INVALID_ARGUMENTS, "View name too long for filesystem storage: " + new SchemaTableName(schema, table));
        }
    }

    // '/' and NUL are the only characters Linux and macOS forbid in a filename. macOS filesystems ignore normalization and
    // case differences, so only NFC, case-folded names are accepted to keep distinct views from sharing one file.
    private static boolean isStorable(String name) {
        return name.indexOf('/') < 0 && name.indexOf('\0') < 0 && Normalizer.isNormalized(name, Normalizer.Form.NFC)
                && name.codePoints().allMatch(c -> Character.toLowerCase(Character.toUpperCase(c)) == c);
    }

    private static String fileName(String schema, String table) {
        return schema + "." + table + ".json";
    }

    // refuses any path outside the flat directory layout, independent of the name rules above
    private Path viewPath(String schema, String table) {
        try {
            Path path = dir.toPath().resolve(fileName(schema, table));
            if (dir.toPath().equals(path.getParent())) return path;
        } catch (InvalidPathException e) {
            // fall through to rejection
        }
        throw new TrinoException(INVALID_ARGUMENTS, "View name not supported by filesystem storage: " + new SchemaTableName(schema, table));
    }

}
