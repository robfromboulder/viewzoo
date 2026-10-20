// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.SchemaTableName;

import java.util.Map;

import static java.util.Locale.ENGLISH;

public interface ViewZooStorageClient {

    Map<SchemaTableName, ConnectorViewDefinition> getViews();

    void createView(String schema, String table, ConnectorViewDefinition definition);

    void dropView(String schema, String table);

    void renameView(String sourceSchema, String sourceTable, String targetSchema, String targetTable);

    // Trino lowercases identifiers, so any other name would load as a view whose operations miss its stored entry
    static boolean isLoadable(String name) {
        return name != null && !name.isEmpty() && name.equals(name.toLowerCase(ENGLISH));
    }

}
