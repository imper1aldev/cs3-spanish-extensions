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

data class LinkData(
    val id: String,
    val media_type: String,
)

data class Images(
    val poster_tall: List<ArrayList<Image>>? = null,
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
    val ids: List<Pair<String, String>>,
)

/*data class VideoStreams(
    val streams: Stream? = null,
    val subtitles: JsonObject? = null,
    val audio_locale: String? = null,
) {
        data class Stream(
        val adaptive_hls: JsonObject,
    )
}*/

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