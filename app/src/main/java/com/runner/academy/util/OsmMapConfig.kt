package com.runner.academy.util

import android.content.Context
import android.util.Log
import org.osmdroid.config.Configuration
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OSM tile policy requires a unique, identifiable HTTP User-Agent.
 * See https://operations.osmfoundation.org/policies/tiles/
 *
 * Always call [apply] after [Configuration.load] — load() restores prefs and can
 * overwrite the agent with a blocked library default.
 */
object OsmMapConfig {

    private const val TAG = "OsmMapConfig"
    private const val PREFS_NAME = "osmdroid"
    private const val PREF_UA_MIGRATION = "osm_user_agent_v4_com_runner_academy"

    /**
     * Example from OSM policy: AppName/version (+url; contact).
     * Must not be a library default or a spoofed browser UA.
     */
    const val USER_AGENT =
        "Runner.Academy/1.0 (com.runner.academy; +https://runner.academy)"

    fun apply(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        Configuration.getInstance().apply {
            load(appContext, prefs)
            userAgentValue = USER_AGENT
            // Prefer local cache; respect OSM guidance to cache tiles for several days
            expirationOverrideDuration = TimeUnit.DAYS.toMillis(7)
            osmdroidBasePath = File(appContext.cacheDir, "osmdroid").also { it.mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "tiles").also { it.mkdirs() }
            save(appContext, prefs)
        }

        clearStaleDeniedTilesOnce(prefs)
    }

    /**
     * Failed 403/AccessDenied responses may sit in the tile cache as blank tiles.
     * Clear once after adopting a compliant User-Agent.
     */
    private fun clearStaleDeniedTilesOnce(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(PREF_UA_MIGRATION, false)) return

        try {
            val cache = Configuration.getInstance().osmdroidTileCache
            if (cache != null && cache.exists()) {
                cache.deleteRecursively()
                cache.mkdirs()
                Log.i(TAG, "Cleared OSM tile cache after User-Agent update")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear OSM tile cache: ${e.message}")
        }

        prefs.edit().putBoolean(PREF_UA_MIGRATION, true).apply()
    }
}
