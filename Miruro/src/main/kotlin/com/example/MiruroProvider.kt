package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.EnumSet

class MiruroProvider : MainAPI() {
    override var mainUrl = "https://miruro.bz"
    override var name = "Miruro"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearch = true

    private val apiUrl = "https://api.miruro.su"

    // ─── Data Classes ──────────────────────────────────────────────────────────

    data class TvInfo(
        val showType: String? = null,
        val duration: String? = null,
        val sub: Int? = null,
        val dub: Int? = null,
        val eps: Int? = null
    )

    data class AnimeEntry(
        val id: String,
        val data_id: Int? = null,
        val title: String,
        val japanese_title: String? = null,
        val poster: String? = null,
        val description: String? = null,
        val tvInfo: TvInfo? = null
    )

    data class SearchApiResponse(
        val success: Boolean,
        val results: List<AnimeEntry>
    )

    data class AnimeInfoData(
        val id: String,
        val data_id: Int? = null,
        val title: String,
        val japanese_title: String? = null,
        val poster: String? = null,
        val showType: String? = null,
        val animeInfo: Map<String, Any>? = null
    )

    data class AnimeInfoResponse(
        val success: Boolean,
        val results: AnimeInfoResults
    )

    data class AnimeInfoResults(
        val data: AnimeInfoData
    )

    data class Episode(
        val episode_no: Int,
        val id: String,
        val data_id: Int? = null,
        val title: String? = null,
        val jname: String? = null
    )

    data class EpisodeListResults(
        val totalEpisodes: Int,
        val episodes: List<Episode>
    )

    data class EpisodeListResponse(
        val success: Boolean,
        val results: EpisodeListResults
    )

    data class StreamLink(
        val file: String,
        val type: String? = null
    )

    data class SubtitleTrack(
        val file: String,
        val label: String? = null,
        val kind: String? = null,
        val default: Boolean? = null
    )

    data class StreamingEntry(
        val id: Int? = null,
        val type: String? = null,
        val link: StreamLink,
        val tracks: List<SubtitleTrack>? = null,
        val server: String? = null
    )

    data class StreamResults(
        val streamingLink: List<StreamingEntry>,
        val servers: List<Map<String, Any>>? = null
    )

    data class StreamResponse(
        val success: Boolean,
        val results: StreamResults
    )

    // ─── Main Page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$apiUrl/api/most-popular" to "Most Popular",
        "$apiUrl/api/top-airing" to "Top Airing",
        "$apiUrl/api/recently-updated" to "Recently Updated",
        "$apiUrl/api/recently-added" to "Recently Added",
        "$apiUrl/api/movie" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}?page=$page"
        val res = app.get(url).parsed<CategoryResponse>()
        val items = res.results.data.map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    data class CategoryResults(val data: List<AnimeEntry>, val totalPages: Int? = null)
    data class CategoryResponse(val success: Boolean, val results: CategoryResults)

    // ─── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "$apiUrl/api/search",
            params = mapOf("keyword" to query)
        ).parsed<SearchApiResponse>()
        return res.results.map { it.toSearchResponse() }
    }

    // ─── Load (Anime Page + Episodes) ─────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val animeId = url
            .removePrefix("$mainUrl/watch/")
            .removePrefix("$mainUrl/")
            .trim('/')

        val infoRes = app.get(
            "$apiUrl/api/info",
            params = mapOf("id" to animeId)
        ).parsed<AnimeInfoResponse>()

        val info = infoRes.results.data
        val animeInfo = info.animeInfo ?: emptyMap()

        val episodeRes = app.get("$apiUrl/api/episodes/$animeId")
            .parsed<EpisodeListResponse>()

        val episodes = episodeRes.results.episodes.map { ep ->
            Episode(
                data = "$animeId,${ep.id},${ep.episode_no}",
                name = ep.title ?: "Episode ${ep.episode_no}",
                episode = ep.episode_no
            )
        }

        val showType = when (info.showType?.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(info.title, url, showType) {
            this.posterUrl = info.poster
            this.plot = animeInfo["Overview"]?.toString()
            this.tags = (animeInfo["Genres"] as? List<*>)?.map { it.toString() }
            this.showStatus = when (animeInfo["Status"]?.toString()?.lowercase()) {
                "finished airing" -> ShowStatus.Completed
                "currently airing" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ─── Load Links (Stream URLs) ──────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(",")
        if (parts.size < 2) return false
        val episodeId = parts[1]

        listOf("sub", "dub").forEach { type ->
            try {
                val streamRes = app.get(
                    "$apiUrl/api/stream",
                    params = mapOf(
                        "id" to episodeId,
                        "server" to "hd-1",
                        "type" to type
                    )
                ).parsed<StreamResponse>()

                streamRes.results.streamingLink.forEach { stream ->
                    val m3u8Url = stream.link.file
                    if (m3u8Url.isNotBlank()) {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "$name ${type.uppercase()} - ${stream.server ?: "HD-1"}",
                                url = m3u8Url,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = m3u8Url.contains(".m3u8")
                            )
                        )
                        stream.tracks?.forEach { track ->
                            if (track.kind == "captions" || track.kind == "subtitles") {
                                subtitleCallback(
                                    SubtitleFile(
                                        lang = track.label ?: "Unknown",
                                        url = track.file
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Type not available, skip
            }
        }
        return true
    }

    // ─── Helper: AnimeEntry → SearchResponse ──────────────────────────────────

    private fun AnimeEntry.toSearchResponse(): AnimeSearchResponse {
        return newAnimeSearchResponse(
            name = title,
            url = "$mainUrl/watch/$id",
            type = TvType.Anime
        ) {
            this.posterUrl = poster
            this.dubStatus = when {
                tvInfo?.dub != null && tvInfo.dub > 0 && tvInfo.sub != null && tvInfo.sub > 0 ->
                    EnumSet.of(DubStatus.Dubbed, DubStatus.Subbed)
                tvInfo?.dub != null && tvInfo.dub > 0 ->
                    EnumSet.of(DubStatus.Dubbed)
                else ->
                    EnumSet.of(DubStatus.Subbed)
            }
        }
    }
}
