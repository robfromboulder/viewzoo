// © 2024-2026 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import io.trino.spi.TrinoException;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.testing.TestingConnectorContext;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.trino.spi.transaction.IsolationLevel.READ_COMMITTED;
import static io.trino.spi.transaction.IsolationLevel.SERIALIZABLE;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TestViewZooConnector {

    @Test
    public void testLifecycle() throws IOException {
        Path dir = Files.createTempDirectory("viewzoo");
        try {
            Map<String, String> config = Map.of("viewzoo.storage_type", "filesystem", "viewzoo.dir", dir.toString());
            Connector connector = new ViewZooConnectorFactory().create("viewzoo", config, new TestingConnectorContext());
            try {
                ConnectorTransactionHandle transaction = connector.beginTransaction(READ_COMMITTED, false, true);
                assertTrue(connector.getMetadata(null, transaction) instanceof ViewZooMetadata);
                expectThrows(TrinoException.class, () -> connector.beginTransaction(SERIALIZABLE, false, true));
            } finally {
                connector.shutdown();
            }
        } finally {
            Files.delete(dir);
        }
    }

}
