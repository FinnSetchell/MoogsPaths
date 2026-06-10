package com.finndog.moogs_paths;

import com.finndog.moogs_paths.commands.PathsDebugCommand;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.debug.PathDebugTimer;
import com.finndog.moogs_paths.platform.Services;
import com.finndog.moogs_paths.world.deferred.PlacementTickPump;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class MoogsPathsCommon {
    private static final AtomicBoolean DEBUG_INITIALISED = new AtomicBoolean(false);

    public static void init() {
        MoogsPathsDatapackRegistries.register();
        Services.PLATFORM.registerServerStartingListener(server -> {
            MoogsPathsDatapackRegistries.invalidateDerivedViews();
            PathDataManager.onServerStart(server.getStructureManager());
            PlacementTickPump.onServerStarting(server);
            if(Constants.ENABLE_DEBUG_TIMER && DEBUG_INITIALISED.compareAndSet(false, true)) {
                Path logDir = server.getServerDirectory().toPath().resolve("logs");
                PathDebugTimer.init(logDir);
                Runtime.getRuntime().addShutdownHook(new Thread(PathDebugTimer::close, "moogs_paths_debug-shutdown"));
            }
        });
        Services.PLATFORM.registerChunkLoadListener(PlacementTickPump::onChunkLoad);
        Services.PLATFORM.registerServerTickEndListener(PlacementTickPump::onServerTickEnd);
        Services.PLATFORM.registerServerStoppingListener(PlacementTickPump::onServerStopping);
        Services.PLATFORM.registerCommandListener(PathsDebugCommand::register);
    }
}
