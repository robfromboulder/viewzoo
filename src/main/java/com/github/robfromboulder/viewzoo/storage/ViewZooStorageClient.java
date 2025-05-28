package com.github.robfromboulder.viewzoo.storage;

import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.SchemaTableName;

import java.util.Map;

public interface ViewZooStorageClient {
    Map<SchemaTableName, ConnectorViewDefinition> getViews();
    void createView(String schema, String table, ConnectorViewDefinition definition);
    void dropView(String schema, String table);
}
