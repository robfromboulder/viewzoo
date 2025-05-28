package com.github.robfromboulder.viewzoo.config;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

public class TestViewZooJdbcConfig {

    @Test
    public void testDefaults() {
        assertRecordedDefaults(recordDefaults(ViewZooJdbcConfig.class).setJdbcUrl(null).setJdbcUser(null).setJdbcPassword(null));
    }

    @Test
    public void testExplicitPropertyMappings() {
        Map<String, String> properties = ImmutableMap.<String, String>builder().put(
                "viewzoo.jdbc_url",
                "jdbc:postgresql://localhost:5432/viewzoo"
        ).put("viewzoo.jdbc_user", "admin").put("viewzoo.jdbc_password", "secret").build();

        ViewZooJdbcConfig expected = new ViewZooJdbcConfig().setJdbcUrl("jdbc:postgresql://localhost:5432/viewzoo")
                .setJdbcUser("admin").setJdbcPassword("secret");

        assertFullMapping(properties, expected);
    }
}
