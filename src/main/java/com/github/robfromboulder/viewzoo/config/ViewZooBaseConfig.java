// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.config;

import io.airlift.configuration.Config;

public class ViewZooBaseConfig {

    private String storageType = "filesystem";

    public String getStorageType() {
        return storageType;
    }

    @Config("viewzoo.storage_type")
    public ViewZooBaseConfig setStorageType(String storageType) {
        this.storageType = storageType;
        return this;
    }

}
