// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

public class TestViewZooConfig {

    @Test
    public void testDefaults() {
        assertRecordedDefaults(recordDefaults(ViewZooFilesystemConfig.class).setDir(null));
    }

    @Test
    public void testExplicitPropertyMappings() throws IOException {
        Path dir = Files.createTempFile(null, null);
        Map<String, String> properties = new ImmutableMap.Builder<String, String>().put("viewzoo.dir", dir.toString()).build();
        ViewZooFilesystemConfig expected = new ViewZooFilesystemConfig().setDir(dir.toString());
        assertFullMapping(properties, expected);
    }

}
