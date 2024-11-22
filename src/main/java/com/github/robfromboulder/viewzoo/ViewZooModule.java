// © 2024 Rob Dickinson (robfromboulder)

package com.github.robfromboulder.viewzoo;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;

import static io.airlift.configuration.ConfigBinder.configBinder;

public class ViewZooModule implements Module {

    @Override
    public void configure(Binder binder) {
        configBinder(binder).bindConfig(ViewZooConfig.class);
        binder.bind(ViewZooConnector.class).in(Scopes.SINGLETON);
        binder.bind(ViewZooMetadata.class).in(Scopes.SINGLETON);
    }

}
