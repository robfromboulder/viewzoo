// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo.storage;

import com.github.robfromboulder.viewzoo.config.ViewZooBaseConfig;
import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import com.github.robfromboulder.viewzoo.config.ViewZooJdbcConfig;
import io.trino.spi.TrinoException;
import org.testng.annotations.Test;

import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class TestViewZooStorageClientProvider {

    @Test
    public void testInvalidStorageType() {
        ViewZooStorageClientProvider provider = new ViewZooStorageClientProvider(new ViewZooBaseConfig().setStorageType("s3"), new ViewZooJdbcConfig(), new ViewZooFilesystemConfig());
        TrinoException e = expectThrows(TrinoException.class, provider::get);
        assertEquals(e.getErrorCode(), CONFIGURATION_INVALID.toErrorCode());
        assertEquals(e.getMessage(), "Invalid viewzoo.storage_type: s3");
    }

}
