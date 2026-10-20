// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.robfromboulder.viewzoo.config.ViewZooJdbcConfig;
import io.airlift.log.Logger;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.ViewNotFoundException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static com.github.robfromboulder.viewzoo.storage.ViewZooStorageClient.isLoadable;
import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.util.Objects.requireNonNull;

public class ViewZooJdbcClient implements ViewZooStorageClient {

    private static final Logger log = Logger.get(ViewZooJdbcClient.class);

    // PostgreSQL SQLSTATE for unique_violation
    private static final String UNIQUE_VIOLATION = "23505";

    public ViewZooJdbcClient(ViewZooJdbcConfig config, ObjectMapper mapper) {
        this.mapper = mapper;

        // read configuration
        this.properties = new Properties();
        properties.setProperty("user", requireNonNull(config.getJdbcUser(), "jdbcUser is null"));
        properties.setProperty("password", requireNonNull(config.getJdbcPassword(), "jdbcPassword is null"));
        this.url = requireNonNull(config.getJdbcUrl(), "jdbcUrl is null");

        // initialize database
        try (Connection connection = getConnection()) {
            String sql = "CREATE TABLE IF NOT EXISTS viewzoo (schema VARCHAR(255), view_name VARCHAR(255), definition TEXT, PRIMARY KEY (schema, view_name))";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.execute();
            }
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to initialize database: " + e.getMessage(), e);
        }
    }

    private final ObjectMapper mapper;
    private final Properties properties;
    private final String url;

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, properties);
    }

    @Override
    public Map<SchemaTableName, ConnectorViewDefinition> getViews() {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT schema, view_name, definition FROM viewzoo")) {
            Map<SchemaTableName, ConnectorViewDefinition> views = new HashMap<>();
            while (resultSet.next()) {
                String schemaName = resultSet.getString("schema");
                String viewName = resultSet.getString("view_name");
                String definitionText = resultSet.getString("definition");
                if (!isLoadable(schemaName) || !isLoadable(viewName)) {
                    log.warn("Skipping row whose name is not a view name Trino can create: %s.%s", schemaName, viewName);
                    continue;
                }
                try {
                    views.put(new SchemaTableName(schemaName, viewName), parse(definitionText));
                } catch (IOException e) {
                    log.warn("Skipping row whose definition is not a view definition: %s.%s: %s", schemaName, viewName, e.getMessage());
                }
            }
            return views;
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to retrieve view definitions: " + e.getMessage(), e);
        }
    }

    @Override
    public void createView(String schema, String table, ConnectorViewDefinition definition) {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO viewzoo VALUES (?, ?, ?) on conflict (schema, view_name) do update set definition = EXCLUDED.definition")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, mapper.writeValueAsString(definition));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to insert view definition: " + e.getMessage(), e);
        } catch (JsonProcessingException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to serialize view definition: " + e.getMessage(), e);
        }
    }

    @Override
    public void dropView(String schema, String table) {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM viewzoo WHERE schema=? AND view_name=?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to delete view definition: " + e.getMessage(), e);
        }
    }

    @Override
    public void renameView(String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE viewzoo SET schema=?, view_name=? WHERE schema=? AND view_name=?")) {
            statement.setString(1, targetSchema);
            statement.setString(2, targetTable);
            statement.setString(3, sourceSchema);
            statement.setString(4, sourceTable);
            if (statement.executeUpdate() == 0) throw new ViewNotFoundException(new SchemaTableName(sourceSchema, sourceTable));
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) throw new TrinoException(ALREADY_EXISTS, "View already exists: " + new SchemaTableName(targetSchema, targetTable), e);
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to rename view definition: " + e.getMessage(), e);
        }
    }

    // a NULL column and the JSON literal null both parse without error to no definition
    private ConnectorViewDefinition parse(String text) throws IOException {
        if (text == null) throw new IOException("Definition is NULL");
        ConnectorViewDefinition definition = mapper.readValue(text, ConnectorViewDefinition.class);
        if (definition == null) throw new IOException("Definition is JSON null");
        return definition;
    }

}
