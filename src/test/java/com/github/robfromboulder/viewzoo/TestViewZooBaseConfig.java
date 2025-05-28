// © 2024-2025 Rob Dickinson (robfromboulder)
package com.github.robfromboulder.viewzoo;

import com.github.robfromboulder.viewzoo.config.ViewZooBaseConfig;
import com.github.robfromboulder.viewzoo.config.ViewZooJdbcConfig;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

public class TestViewZooBaseConfig {

    @Test
    public void testDefaults() {
        assertRecordedDefaults(recordDefaults(ViewZooBaseConfig.class).setStorageType("filesystem"));
    }

}
