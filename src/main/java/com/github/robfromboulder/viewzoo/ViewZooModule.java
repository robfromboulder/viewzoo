// © 2024-2025 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.github.robfromboulder.viewzoo.config.ViewZooBaseConfig;
import com.github.robfromboulder.viewzoo.config.ViewZooFilesystemConfig;
import com.github.robfromboulder.viewzoo.config.ViewZooJdbcConfig;
import com.github.robfromboulder.viewzoo.storage.ViewZooStorageClient;
import com.github.robfromboulder.viewzoo.storage.ViewZooStorageClientProvider;
import com.google.inject.Binder;
import com.google.inject.Scopes;
import io.airlift.configuration.AbstractConfigurationAwareModule;

import static io.airlift.configuration.ConfigBinder.configBinder;

public class ViewZooModule extends AbstractConfigurationAwareModule {

    public void setup(Binder binder) {
        configBinder(binder).bindConfig(ViewZooBaseConfig.class);
        configBinder(binder).bindConfig(ViewZooFilesystemConfig.class);
        configBinder(binder).bindConfig(ViewZooJdbcConfig.class);
        binder.bind(ViewZooConnector.class).in(Scopes.SINGLETON);
        binder.bind(ViewZooMetadata.class).in(Scopes.SINGLETON);
        binder.bind(ViewZooStorageClient.class).toProvider(ViewZooStorageClientProvider.class).in(Scopes.SINGLETON);
    }

}
