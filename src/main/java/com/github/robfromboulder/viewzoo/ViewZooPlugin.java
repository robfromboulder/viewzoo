// © 2024 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.google.common.collect.ImmutableList;
import io.trino.spi.Plugin;
import io.trino.spi.connector.ConnectorFactory;

public class ViewZooPlugin implements Plugin {

    @Override
    public Iterable<ConnectorFactory> getConnectorFactories() {
        return ImmutableList.of(new ViewZooConnectorFactory());
    }

}
