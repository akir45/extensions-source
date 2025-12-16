package eu.kanade.tachiyomi.extension.en.CloudflareBypass

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class CloudflareBypassFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        CloudflareBypassSource()
    )
}
