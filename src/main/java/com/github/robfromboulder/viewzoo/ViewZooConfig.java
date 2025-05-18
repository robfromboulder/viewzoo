// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import io.airlift.configuration.Config;

public class ViewZooConfig {

    private String dir;
    private String storageType = "filesystem";
    private String jdbcUrl;
    private String jdbcUser;
    private String jdbcPassword;

    public String getDir() {
        return dir;
    }
    public String getStorageType() {
        return storageType;
    }
    public String getJdbcUrl() {
        return jdbcUrl;
    }
    public String getJdbcUser() {
        return jdbcUser;
    }
    public String getJdbcPassword() {
        return jdbcPassword;
    }


    @Config("viewzoo.dir")
    public ViewZooConfig setDir(String s) {
        this.dir = s;
        return this;
    }

    @Config("viewzoo.storage_type")
    public ViewZooConfig setStorageType(String s) {
        this.storageType = s;
        return this;
    }

    @Config("viewzoo.jdbc_url")
    public ViewZooConfig setJdbcUrl(String s) {
        this.jdbcUrl = s;
        return this;
    }

    @Config("viewzoo.jdbc_user")
    public ViewZooConfig setJdbcUser(String s) {
        this.jdbcUser = s;
        return this;
    }

    @Config("viewzoo.jdbc_password")
    public ViewZooConfig setJdbcPassword(String s) {
        this.jdbcPassword = s;
        return this;
    }
}
