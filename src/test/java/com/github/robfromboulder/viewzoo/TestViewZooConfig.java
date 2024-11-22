// © 2024 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.*;

public class TestViewZooConfig {

    @Test
    public void testDefaults() {
        assertRecordedDefaults(recordDefaults(ViewZooConfig.class).setViewsDir(null));
    }

    @Test
    public void testExplicitPropertyMappings() throws IOException {
        Path p = Files.createTempFile(null, null);

        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("viewzoo.views.dir", p.toString())
                .build();

        ViewZooConfig expected = new ViewZooConfig()
                .setViewsDir(p.toString());

        assertFullMapping(properties, expected);
    }

}
