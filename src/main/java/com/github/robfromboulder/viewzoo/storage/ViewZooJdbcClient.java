// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.robfromboulder.viewzoo.config.ViewZooJdbcConfig;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.SchemaTableName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.util.Objects.requireNonNull;

public class ViewZooJdbcClient implements ViewZooStorageClient {

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
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to initialize database" + e.getMessage());
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
        try (Connection connection = getConnection()) {
            Map<SchemaTableName, ConnectorViewDefinition> views = new HashMap<>();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT schema, view_name, definition FROM viewzoo");
            while (resultSet.next()) {
                String schemaName = resultSet.getString("schema");
                String viewName = resultSet.getString("view_name");
                String definitionText = resultSet.getString("definition");
                ConnectorViewDefinition def = mapper.readValue(definitionText, ConnectorViewDefinition.class);
                views.put(new SchemaTableName(schemaName, viewName), def);
            }
            return views;
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to retrieve view definitions: " + e.getMessage());
        } catch (JsonProcessingException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to parse view definitions: " + e.getMessage());
        }

    }

    @Override
    public void createView(String schema, String table, ConnectorViewDefinition definition) {
        try (Connection connection = getConnection()) {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO viewzoo VALUES (?, ?, ?)");
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, mapper.writeValueAsString(definition));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to insert view definition: " + e.getMessage());
        } catch (JsonProcessingException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to serialize view definition: " + e.getMessage());
        }
    }

    @Override
    public void dropView(String schema, String table) {
        try (Connection connection = getConnection()) {
            PreparedStatement statement = connection.prepareStatement("DELETE FROM viewzoo WHERE schema=? AND view_name=?");
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to delete view definition: " + e.getMessage());
        }
    }

}
