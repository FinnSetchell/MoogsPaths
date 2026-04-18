package com.finndog.moogs_paths;

import com.finndog.moogs_paths.commands.PathsDebugCommand;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.platform.Services;

public class MoogsPathsCommon {
    public static void init() {
        MoogsPathsDatapackRegistries.register();
        Services.PLATFORM.registerServerStartingListener(server -> {
            MoogsPathsDatapackRegistries.invalidateDerivedViews();
            PathDataManager.onServerStart(server.getStructureManager());
        });
        Services.PLATFORM.registerCommandListener(PathsDebugCommand::register);
    }
}
