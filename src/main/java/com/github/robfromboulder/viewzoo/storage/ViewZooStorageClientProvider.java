package com.github.robfromboulder.viewzoo.storage;

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
        return switch (config.getStorageType().toLowerCase()) {
            case "jdbc" -> new ViewZooJdbcClient(jdbcConfig);
            case "filesystem" -> new ViewZooLocalFileSystemClient(filesystemConfig);
            default -> throw new TrinoException(CONFIGURATION_INVALID, "Invalid viewzoo.storage_type: " + config.getStorageType());
        };
    }
}
