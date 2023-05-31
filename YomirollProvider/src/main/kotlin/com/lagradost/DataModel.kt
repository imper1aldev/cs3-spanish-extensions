package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty

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
    @JsonProperty("audio_locale") val audio_locale: String? = null,
)
