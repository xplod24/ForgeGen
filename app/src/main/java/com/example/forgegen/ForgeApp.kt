package com.example.forgegen

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/* ============================================================================
 * APPLICATION
 * Holds the one image loader of the app (Coil). It used to be built by the activity, so every rotation or theme
 * change made a new one and lost the images already in memory.
 * ============================================================================ */
class ForgeApp :
    Application(),
    ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            // Built on the first image request, from the app's one HTTP client (before the settings are loaded it
            // has no gallery cookie and no timeouts). Thumbnails and previews are kept for 30 days.
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
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes((2.5 * 1024 * 1024 * 1024).toLong())
                    .build()
            }.build()
}
