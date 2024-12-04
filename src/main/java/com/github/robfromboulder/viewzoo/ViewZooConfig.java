// © 2024 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import io.airlift.configuration.Config;

public class ViewZooConfig {

    private String dir;

    public String getDir() {
        return dir;
    }

    @Config("viewzoo.dir")
    public ViewZooConfig setDir(String s) {
        this.dir = s;
        return this;
    }

}
