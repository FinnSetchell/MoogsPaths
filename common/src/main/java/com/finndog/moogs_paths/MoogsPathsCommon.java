package com.finndog.moogs_paths;

import com.finndog.moogs_paths.data.PathDataManager;

public class MoogsPathsCommon {
    public static void init() {
        Services.PLATFORM.registerReloadListeners(PathDataManager.createListeners());
    }
}
