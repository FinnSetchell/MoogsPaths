package com.finndog.moogs_paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

	// Path timings and counters (logs/moogs_paths_debug_timings.jsonl). Off unless the JVM runs with
	// -Dmoogs_paths.debug_timer=true, which only the dev run configs pass, so a released jar never
	// writes them. The JIT folds the guarded calls away once this is known to be false.
	public static final boolean ENABLE_DEBUG_TIMER = Boolean.getBoolean("moogs_paths.debug_timer");
	public static final String MOD_ID = "moogs_paths";
	public static final String MOD_NAME = "Moog's Paths";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);
}