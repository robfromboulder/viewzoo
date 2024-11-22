// © 2024 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import io.airlift.configuration.Config;

public class ViewZooConfig {

    private String viewsDir = null;

    public String getViewsDir() {
        return viewsDir;
    }

    @Config("viewzoo.views.dir")
    public ViewZooConfig setViewsDir(String dir) {
        this.viewsDir = dir;
        return this;
    }

}
