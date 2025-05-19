// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.config;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigSecuritySensitive;

public class ViewZooJdbcConfig {

    private String jdbcUrl;
    private String jdbcUser;
    private String jdbcPassword;

    public String getJdbcUrl() {
        return jdbcUrl;
    }
    public String getJdbcUser() {
        return jdbcUser;
    }
    public String getJdbcPassword() {
        return jdbcPassword;
    }

    @Config("viewzoo.jdbc_url")
    public ViewZooJdbcConfig setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        return this;
    }

    @Config("viewzoo.jdbc_user")
    public ViewZooJdbcConfig setJdbcUser(String jdbcUser) {
        this.jdbcUser = jdbcUser;
        return this;
    }

    @Config("viewzoo.jdbc_password")
    @ConfigSecuritySensitive
    public ViewZooJdbcConfig setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
        return this;
    }
}
