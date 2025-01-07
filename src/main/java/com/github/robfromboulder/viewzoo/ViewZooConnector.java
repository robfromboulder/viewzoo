// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import io.airlift.bootstrap.LifeCycleManager;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.transaction.IsolationLevel;

import javax.inject.Inject;

import static io.trino.spi.transaction.IsolationLevel.READ_COMMITTED;
import static io.trino.spi.transaction.IsolationLevel.checkConnectorSupports;
import static java.util.Objects.requireNonNull;

public class ViewZooConnector implements Connector {

    @Inject
    public ViewZooConnector(LifeCycleManager lcm, ViewZooMetadata metadata) {
        this.lcm = requireNonNull(lcm, "lcm is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    private final LifeCycleManager lcm;
    private final ViewZooMetadata metadata;

    @Override
    public ConnectorTransactionHandle beginTransaction(IsolationLevel isolationLevel, boolean readOnly, boolean autoCommit) {
        checkConnectorSupports(READ_COMMITTED, isolationLevel);
        return ViewZooTransactionHandle.INSTANCE;
    }

    @Override
    public ConnectorMetadata getMetadata(ConnectorSession session, ConnectorTransactionHandle transactionHandle) {
        return metadata;
    }

    @Override
    public final void shutdown() {
        lcm.stop();
    }

}
