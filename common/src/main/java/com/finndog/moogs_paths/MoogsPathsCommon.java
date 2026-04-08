package com.finndog.moogs_paths;

import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.platform.Services;

public class MoogsPathsCommon {
    public static void init() {
        Services.PLATFORM.registerReloadListeners(PathDataManager.createListeners());
        Services.PLATFORM.registerServerStartingListener(server ->
            PathDataManager.onServerStart(server.getStructureManager()));
    }
}
