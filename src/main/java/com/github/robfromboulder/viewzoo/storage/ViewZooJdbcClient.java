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
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.util.Objects.requireNonNull;


public class ViewZooJdbcClient implements ViewZooStorageClient {
    private final String jdbcUrl;
    private final Properties connectionProperties;
    private final ObjectMapper mapper;


    public ViewZooJdbcClient(ViewZooJdbcConfig config, ObjectMapper mapper) {
        this.jdbcUrl = requireNonNull(config.getJdbcUrl(), "jdbcUrl is null");
        String jdbcUser = requireNonNull(config.getJdbcUser(), "jdbcUser is null");
        String jdbcPassword = requireNonNull(config.getJdbcPassword(), "jdbcPassword is null");
        this.connectionProperties = new Properties();
        connectionProperties.setProperty("user", jdbcUser);
        connectionProperties.setProperty("password", jdbcPassword);

        this.mapper = mapper;

        initializeViewStore();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, connectionProperties);
    }

    private void initializeViewStore() {
        try (Connection connection = getConnection()) {
            String createViewDefTableSql = "CREATE TABLE IF NOT EXISTS viewzoo (" +
                    "schema VARCHAR(255), " +
                    "view_name VARCHAR(255), " +
                    "definition TEXT, " +
                    "PRIMARY KEY (schema, view_name))";
            try (PreparedStatement statement = connection.prepareStatement(createViewDefTableSql)) {
                statement.execute();
            }
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to initialize database" + e.getMessage());
        }
    }

    @Override
    public Map<SchemaTableName, ConnectorViewDefinition> getViews() {
        String getViewDefsTableSql = "SELECT schema, view_name, definition FROM viewzoo";
        Map<SchemaTableName, ConnectorViewDefinition> viewDefinitions = new HashMap<>();

        try (Connection connection = getConnection()) {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(getViewDefsTableSql);
            while (resultSet.next()) {
                String schemaName = resultSet.getString("schema");
                String viewName = resultSet.getString("view_name");
                String definitionText = resultSet.getString("definition");

                ConnectorViewDefinition def = mapper.readValue(definitionText, ConnectorViewDefinition.class);
                viewDefinitions.put(new SchemaTableName(schemaName, viewName), def);
            }

            return viewDefinitions;
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to retrieve view definitions: " + e.getMessage());
        } catch (JsonProcessingException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to parse view definitions: " + e.getMessage());
        }

    }

    @Override
    public void createView(String schema, String table, ConnectorViewDefinition definition) {
        String createViewSql = "INSERT INTO viewzoo VALUES (?, ?, ?)";

        try (Connection connection = getConnection()) {
            PreparedStatement statement = connection.prepareStatement(createViewSql);
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
        String createViewSql = "DELETE FROM viewzoo WHERE schema=? AND view_name=?";

        try (Connection connection = getConnection()) {
            PreparedStatement statement = connection.prepareStatement(createViewSql);
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to delete view definition: " + e.getMessage());
        }
    }
}
