package com.github.robfromboulder.viewzoo.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.robfromboulder.viewzoo.config.ViewZooBaseConfig;
import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import com.github.robfromboulder.viewzoo.config.ViewZooJdbcConfig;
import com.google.inject.Inject;
import com.google.inject.Provider;
import io.trino.spi.TrinoException;

import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;

public class ViewZooStorageClientProvider implements Provider<ViewZooStorageClient> {
    @Inject
    public ViewZooStorageClientProvider(
            ViewZooBaseConfig config,
            ViewZooJdbcConfig jdbcConfig,
            ViewZooFilesystemConfig filesystemConfig
    ) {
        this.config = config;
        this.jdbcConfig = jdbcConfig;
        this.filesystemConfig = filesystemConfig;
    }

    private final ViewZooBaseConfig config;
    private final ViewZooJdbcConfig jdbcConfig;
    private final ViewZooFilesystemConfig filesystemConfig;

    @Override
    public ViewZooStorageClient get() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new Jdk8Module());

        return switch (config.getStorageType().toLowerCase()) {
            case "jdbc" -> new ViewZooJdbcClient(jdbcConfig, mapper);
            case "filesystem" -> new ViewZooLocalFileSystemClient(filesystemConfig, mapper);
            default -> throw new TrinoException(CONFIGURATION_INVALID, "Invalid viewzoo.storage_type: " + config.getStorageType());
        };
    }
}
