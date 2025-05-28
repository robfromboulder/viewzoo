// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.config;

import io.airlift.configuration.Config;

public class ViewZooFilesystemConfig {

    private String dir;

    public String getDir() {
        return dir;
    }

    @Config("viewzoo.dir")
    public ViewZooFilesystemConfig setDir(String dir) {
        this.dir = dir;
        return this;
    }

}
