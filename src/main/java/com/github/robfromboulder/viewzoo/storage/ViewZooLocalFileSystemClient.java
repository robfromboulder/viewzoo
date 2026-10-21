// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import io.airlift.log.Logger;
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
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.robfromboulder.viewzoo.storage.ViewZooStorageClient.isLoadable;
import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;

public class ViewZooLocalFileSystemClient implements ViewZooStorageClient {

    private static final Logger log = Logger.get(ViewZooLocalFileSystemClient.class);

    /**
     * Longest view file name accepted, in UTF-8 bytes. Linux's limit, enforced on macOS too so a view directory written on
     * either loads on both.
     */
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
            String[] name_pieces = f.getName().split("\\.", -1);
            if ((name_pieces.length != 3) || !isLoadableFile(name_pieces[0]) || !isLoadableFile(name_pieces[1])) {
                log.warn("Skipping file whose name is not a view name Trino can create: %s", f);
                continue;
            }
            try {
                views.put(new SchemaTableName(name_pieces[0], name_pieces[1]), parse(f));
            } catch (IOException e) {
                log.warn("Skipping file whose contents are not a view definition: %s: %s", f, e.getMessage());
            }
        }
        return views;
    }

    @Override
    public void createView(String schema, String table, ConnectorViewDefinition definition, boolean replace) {
        validateName(schema, table);
        Path path = viewPath(schema, table);
        requireNoConflictingFile(path, schema, table);
        Path temp = dir.toPath().resolve(".viewzoo-" + UUID.randomUUID() + ".tmp"); // written beside the view's file, so a failed write never truncates it
        try {
            Files.writeString(temp, mapper.writeValueAsString(definition));
            if (replace) Files.move(temp, path, ATOMIC_MOVE);
            else Files.createLink(path, temp); // link(2) atomically refuses an existing target
        } catch (FileAlreadyExistsException e) {
            throw new TrinoException(ALREADY_EXISTS, "View already exists: " + new SchemaTableName(schema, table), e);
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to write file: " + path + ": " + e.getMessage(), e);
        } finally {
            deleteTempFile(temp);
        }
    }

    /**
     * Deletes a temp file written by createView. Logs rather than throws, since throwing after the link would report a
     * completed create as failed.
     */
    private void deleteTempFile(Path temp) {
        try {
            deleteFile(temp);
        } catch (IOException e) {
            log.warn("Failed to delete temp file: %s: %s", temp, e.getMessage());
        }
    }

    @Override
    public void dropView(String schema, String table) {
        Path path = viewPath(schema, table);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to delete file: " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void renameView(String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
        validateName(targetSchema, targetTable);
        Path source = viewPath(sourceSchema, sourceTable);
        Path target = viewPath(targetSchema, targetTable);
        requireNoConflictingFile(target, targetSchema, targetTable);
        try {
            Files.createLink(target, source); // link(2) atomically refuses an existing target, where Files.move only checks before rename(2) replaces it
        } catch (FileAlreadyExistsException e) {
            throw new TrinoException(ALREADY_EXISTS, "View already exists: " + new SchemaTableName(targetSchema, targetTable), e);
        } catch (NoSuchFileException e) {
            throw new ViewNotFoundException(new SchemaTableName(sourceSchema, sourceTable));
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to link file: " + source + " to " + target + ": " + e.getMessage(), e);
        }
        try {
            deleteFile(source);
        } catch (IOException e) {
            TrinoException failure = new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to delete file: " + source + ": " + e.getMessage(), e);
            // a delete that removed the source despite reporting failure has completed the rename
            // notExists and exists are each true only when confirmed, so an unreadable state falls through to the rollback
            if (Files.notExists(source) && Files.exists(target)) {
                log.warn("Rename completed despite a failed delete: %s: %s", source, e.getMessage());
                return;
            }
            try {
                // the target is removed only while it is still a second link to the source
                // this narrows but can't close the window in which another writer replaces it
                if (Files.isSameFile(source, target)) deleteFile(target);
            } catch (IOException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    /**
     * Deletes a file if it exists. Overridable so tests can fail a delete that follows a link, which needs the same directory
     * permission the link used.
     */
    void deleteFile(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    private static String fileName(String schema, String table) {
        return schema + "." + table + ".json";
    }

    private static boolean isLoadableFile(String name) {
        return isLoadable(name) && isStorable(name);
    }

    /**
     * Returns whether a schema or table name can be stored in a file name identically on Linux and macOS. '/' and NUL are the
     * only characters either forbids in a filename. NFC is required because macOS filesystems ignore normalization differences.
     */
    private static boolean isStorable(String name) {
        return name.indexOf('/') < 0 && name.indexOf('\0') < 0 && Normalizer.isNormalized(name, Normalizer.Form.NFC);
    }

    private ConnectorViewDefinition parse(File f) throws IOException {
        ConnectorViewDefinition definition = mapper.readValue(f, ConnectorViewDefinition.class);
        // the JSON literal null parses without error to no definition
        if (definition == null) throw new IOException("Definition is JSON null");
        return definition;
    }

    private void requireNoConflictingFile(Path path, String schema, String table) {
        if (!Files.exists(path)) return;
        String name = path.getFileName().toString();
        try (Stream<Path> files = Files.list(dir.toPath())) {
            // a case-insensitive filesystem resolves the path to a file stored under a different name
            if (files.noneMatch(p -> p.getFileName().toString().equals(name)))
                throw new TrinoException(ALREADY_EXISTS, "View conflicts with an existing view file in filesystem storage: " + new SchemaTableName(schema, table));
        } catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to list directory: " + dir + ": " + e.getMessage(), e);
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

    /**
     * Returns the path of a view's file in the view directory. Refuses any name that resolves outside the flat directory
     * layout, independent of {@link #validateName}.
     */
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
