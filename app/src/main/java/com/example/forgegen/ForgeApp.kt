package com.example.forgegen

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/* ============================================================================
 * APPLICATION
 * Holds the one image loader of the app (Coil). It used to be built by the activity, so every rotation or theme
 * change made a new one and lost the images already in memory. Also plans the background update check (SelfUpdate).
 * ============================================================================ */
class ForgeApp :
    Application(),
    ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // New releases are looked for every 6 hours, also while the app is closed.
        SelfUpdate.scheduleChecks(this)
        // The home screen widgets are drawn as RemoteViews (3.6.0).
        ForgeWidgets.renderer = WidgetViews
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            // Built on the first image request, from the app's one HTTP client (before the settings are loaded it
            // has no gallery cookie and no timeouts). Thumbnails and previews are kept for 30 days, in a cache of the
            // size chosen in the settings (ImageCache, 3.4.0).
            .okHttpClient {
                ForgeSettingsManager.client
                    .newBuilder()
                    .addNetworkInterceptor { chain ->
                        chain
                            .proceed(chain.request())
                            .newBuilder()
                            .header("Cache-Control", "public, max-age=2592000")
                            .build()
                    }.build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(ImageCache.directory(this))
                    .maxSizeBytes(ImageCache.savedSizeMb(this).also { ImageCache.builtWithMb = it } * 1024L * 1024L)
                    .build()
            }.build()
}
