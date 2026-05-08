package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import java.util.EnumSet

@CloudstreamPlugin
class MiruroV2Provider : MainAPI() {
    override var mainUrl = "https://miruro.to"
    override var name = "Miruro"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"
    override val hasMainPage = true

    private val apiUrl = "https://miruro-api-jet.vercel.app"

    data class Title(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null
    )

    data class CoverImage(
        val large: String? = null,
        val medium: String? = null,
        val color: String? = null
    )

    data class AnimeEntry(
        val id: Int,
        val title: Title? = null,
        val coverImage: CoverImage? = null,
        val format: String? = null,
        val status: String? = null,
        val averageScore: Int? = null,
        val genres: List<String>? = null,
        val description: String? = null,
        val episodes: Int? = null,
        val bannerImage: String? = null
    )

    data class SearchApiResponse(
        val page: Int? = null,
        val perPage: Int? = null,
        val total: Int? = null,
        val hasNextPage: Boolean? = null,
        val results: List<AnimeEntry>? = null
    )

    data class EpisodeItem(
        val id: String,
        val number: Int,
        val title: String? = null,
        val image: String? = null,
        val filler: Boolean? = null
    )

    data class ProviderEpisodes(
        val sub: List<EpisodeItem>? = null,
        val dub: List<EpisodeItem>? = null
    )

    data class Provider(
        val episodes: ProviderEpisodes? = null
    )

    data class EpisodesResponse(
        val providers: Map<String, Provider>? = null
    )

    data class StreamItem(
        val url: String,
        val type: String? = null,
        val quality: String? = null
    )

    data class SubtitleItem(
        val file: String,
        val label: String? = null,
        val kind: String? = null
    )

    data class StreamResponse(
        val streams: List<StreamItem>? = null,
        val subtitles: List<SubtitleItem>? = null
    )

    override val mainPage = mainPageOf(
        "$apiUrl/trending" to "Trending",
        "$apiUrl/popular" to "Popular",
        "$apiUrl/recent" to "Recently Airing",
        "$apiUrl/upcoming" to "Upcoming"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get("${request.data}?page=$page&per_page=20").parsed<SearchApiResponse>()
        val items = res.results?.map { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, items, res.hasNextPage ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "$apiUrl/search",
            params = mapOf("query" to query, "page" to "1", "per_page" to "20")
        ).parsed<SearchApiResponse>()
        return res.results?.map { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val anilistId = url.substringAfterLast("/")
        val info = app.get("$apiUrl/info/$anilistId").parsed<AnimeEntry>()
        val episodesRes = app.get("$apiUrl/episodes/$anilistId").parsed<EpisodesResponse>()

        val preferredOrder = listOf("zoro", "arc", "kiwi")
        val providers = episodesRes.providers ?: emptyMap()
        val selectedProvider = preferredOrder.firstOrNull { providers.containsKey(it) }
            ?: providers.keys.firstOrNull()
        val providerData = selectedProvider?.let { providers[it] }

        val subEpisodes = providerData?.episodes?.sub?.map { ep ->
            newEpisode("$selectedProvider,${ep.id},sub") {
                this.name = ep.title ?: "Episode ${ep.number}"
                this.episode = ep.number
                this.posterUrl = ep.image
            }
        } ?: emptyList()

        val dubEpisodes = providerData?.episodes?.dub?.map { ep ->
            newEpisode("$selectedProvider,${ep.id},dub") {
                this.name = ep.title ?: "Episode ${ep.number}"
                this.episode = ep.number
                this.posterUrl = ep.image
            }
        } ?: emptyList()

        val showType = when (info.format?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        val title = info.title?.english ?: info.title?.romaji ?: "Unknown"

        return newAnimeLoadResponse(title, url, showType) {
            this.posterUrl = info.coverImage?.large
            this.backgroundPosterUrl = info.bannerImage
            this.plot = info.description?.replace(Regex("<[^>]*>"), "")
            this.tags = info.genres
            this.showStatus = when (info.status?.uppercase()) {
                "FINISHED" -> ShowStatus.Completed
                "RELEASING" -> ShowStatus.Ongoing
                else -> null
            }
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(",", limit = 3)
        if (parts.size < 3) return false
        val episodeId = parts[1]

        val streamRes = app.get("$apiUrl/$episodeId").parsed<StreamResponse>()

        streamRes.streams?.forEach { stream ->
            if (stream.url.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name ${stream.quality ?: ""}".trim(),
                        url = stream.url,
                        type = if (stream.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = when (stream.quality) {
                            "1080p" -> Qualities.P1080.value
                            "720p" -> Qualities.P720.value
                            "480p" -> Qualities.P480.value
                            "360p" -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                    }
                )
            }
        }

        streamRes.subtitles?.forEach { sub ->
            subtitleCallback(
                newSubtitleFile(
                    lang = sub.label ?: "Unknown",
                    url = sub.file
                )
            )
        }

        return true
    }

    private fun AnimeEntry.toSearchResponse(): AnimeSearchResponse {
        val title = this.title?.english ?: this.title?.romaji ?: "Unknown"
        return newAnimeSearchResponse(
            name = title,
            url = "$mainUrl/watch/$id",
            type = TvType.Anime
        ) {
            this.posterUrl = this@toSearchResponse.coverImage?.large
        }
    }
}
