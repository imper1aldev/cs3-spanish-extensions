package com.lagradost

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers

data class AccessToken(
    val access_token: String,
    val token_type: String,
    val policy: String? = null,
    val signature: String? = null,
    val key_pair_id: String? = null,
    val bucket: String? = null,
    val policyExpire: Long? = null,
)

data class Policy(
    val cms: Tokens,
) {
        data class Tokens(
        val policy: String,
        val signature: String,
        val key_pair_id: String,
        val bucket: String,
        val expires: String,
    )
}

data class LinkData(
    val id: String,
    val media_type: String,
)

data class Images(
    val poster_tall: List<ArrayList<Image>>? = null,
    val poster_wide: List<ArrayList<Image>>? = null,
) {
        data class Image(
        val width: Int,
        val height: Int,
        val type: String,
        val source: String,
    )
}

data class Anime(
    val id: String,
    val type: String? = null,
    val title: String,
    val description: String,
    val images: Images,
    @JsonProperty("keywords")
    val genres: ArrayList<String>? = null,
    val series_metadata: Metadata? = null,
    @JsonProperty("movie_listing_metadata")
    val movie_metadata: MovieMeta? = null,
    val content_provider: String? = null,
) {
        data class Metadata(
        val maturity_ratings: ArrayList<String>,
        val is_simulcast: Boolean,
        val audio_locales: ArrayList<String>,
        val subtitle_locales: ArrayList<String>,
        val is_dubbed: Boolean,
        val is_subbed: Boolean,
        @JsonProperty("tenant_categories")
        val genres: ArrayList<String>? = null,
    )

        data class MovieMeta(
        val is_dubbed: Boolean,
        val is_subbed: Boolean,
        val maturity_ratings: ArrayList<String>,
        val subtitle_locales: ArrayList<String>,
        @JsonProperty("tenant_categories")
        val genres: ArrayList<String>? = null,
    )
}

data class AnimeResult(
    val total: Int,
    val data: ArrayList<Anime>,
)

data class SearchAnimeResult(
    val data: ArrayList<SearchAnime>,
) {
        data class SearchAnime(
        val type: String,
        val count: Int,
        val items: ArrayList<Anime>,
    )
}

data class SeasonResult(
    val total: Int,
    val data: ArrayList<Season>,
) {
        data class Season(
        val id: String,
        val season_number: Int? = null,
        @JsonProperty("premium_available_date")
        val date: String? = null,
    )
}

data class EpisodeResult(
    val total: Int,
    val data: ArrayList<Episode>,
) {
        data class Episode(
        val audio_locale: String,
        val title: String,
        @JsonProperty("sequence_number")
        val episode_number: Float,
        val episode: String? = null,
        @JsonProperty("episode_air_date")
        val airDate: String? = null,
        val versions: ArrayList<Version>? = null,
        val streams_link: String? = null,
    ) {
                data class Version(
            val audio_locale: String,
            @JsonProperty("media_guid")
            val mediaId: String,
        )
    }
}

data class EpisodeData(
    val ids: List<Pair<String, String>>
)

data class VideoStreams(
    val streams: Stream? = null,
    val subtitles: JsonObject? = null,
    val audio_locale: String? = null,
) {
        data class Stream(
        val adaptive_hls: JsonObject,
    )
}

data class HlsLinks(
    val hardsub_locale: String,
    val url: String,
)

data class Subtitle(
    val locale: String,
    val url: String,
)

fun <T> List<T>.thirdLast(): T? {
    if (size < 3) return null
    return this[size - 3]
}

data class CrunchyrollToken(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("expires_in") val expiresIn: Int? = null,
    @JsonProperty("token_type") val tokenType: String? = null,
    @JsonProperty("scope") val scope: String? = null,
    @JsonProperty("country") val country: String? = null
)

data class Track(val url: String, val lang: String)

data class Video(
    val url: String = "",
    val quality: String = "",
    var videoUrl: String? = null,
    @Transient var uri: Uri? = null, // Deprecated but can't be deleted due to extensions
    val headers: Headers? = null,
    // "url", "language-label-2", "url2", "language-label-2"
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
){
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(url, quality, videoUrl, null, headers, subtitleTracks, audioTracks)

    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri?,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, uri, headers, emptyList(), emptyList())
}

data class CrunchyrollResponses(
    @JsonProperty("data") val data: ArrayList<CrunchyrollData>? = arrayListOf(),
)

data class CrunchyrollData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug_title") val slug_title: String? = null,
    @JsonProperty("season_number") val season_number: Int? = null,
    @JsonProperty("episode_number") val episode_number: Int? = null,
    @JsonProperty("versions") val versions: ArrayList<CrunchyrollVersions>? = null,
    @JsonProperty("streams_link") val streams_link: String? = null,
    @JsonProperty("adaptive_hls") val adaptive_hls: HashMap<String, HashMap<String, String>>? = hashMapOf(),
    @JsonProperty("vo_adaptive_hls") val vo_adaptive_hls: HashMap<String, HashMap<String, String>>? = hashMapOf(),
)

data class CrunchyrollVersions(
    @JsonProperty("audio_locale") val audio_locale: String? = null,
    @JsonProperty("guid") val guid: String? = null,
)

data class CrunchyrollSourcesResponses(
    @JsonProperty("data") val data: ArrayList<CrunchyrollData>? = arrayListOf(),
    @JsonProperty("meta") val meta: CrunchyrollMeta? = null,
)

data class CrunchyrollMeta(
    @JsonProperty("subtitles") val subtitles: HashMap<String, HashMap<String, String>>? = hashMapOf(),
)

data class AnilistResponses(
    @JsonProperty("data") var data: AnilistData? = AnilistData()
)

data class AnilistData(
    @JsonProperty("Media") var Media: AnilistMedia? = AnilistMedia()
)

data class AnilistMedia(
    @JsonProperty("externalLinks") var externalLinks: ArrayList<AnilistExternalLinks> = arrayListOf()
)

data class AnilistExternalLinks(
    @JsonProperty("id") var id: Int? = null,
    @JsonProperty("site") var site: String? = null,
    @JsonProperty("url") var url: String? = null,
    @JsonProperty("type") var type: String? = null,
)

data class MalSyncRes(
    @JsonProperty("Sites") val Sites: Map<String,Map<String,Map<String,String>>>? = null,
)