package com.lagradost

import android.net.Uri
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.RequestBodyTypes
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class YomirollProvider : MainAPI() {

    override var mainUrl = "https://www.crunchyroll.com"
    private val crUrl = "https://beta-api.crunchyroll.com"
    private val crApiUrl = "$crUrl/content/v2"
    private val id: Long = 7463514907068706782

    override var name = "Yomiroll"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val tokenInterceptor by lazy { AccessTokenInterceptor(crUrl) }

    private val df by lazy { DecimalFormat("0.#") }
    private fun String?.isNumeric() = this?.toDoubleOrNull() != null
    private fun parseDate(dateStr: String): Long {
        return runCatching { DateFormatter.parse(dateStr)?.time }.getOrNull() ?: 0L
    }

    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        Pair(
            "$crApiUrl/discover/browse?{start}n=36&sort_by=popularity&locale=en-US",
            "Animes populares"
        ),
        Pair(
            "$crApiUrl/discover/browse?{start}n=36&sort_by=newly_added&locale=en-US",
            "Nuevos animes"
        ),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        val url = request.data.replace("{start}", start)
        val position = request.data.toHttpUrl().queryParameter("start")?.toIntOrNull() ?: 0

        val parsed = app.get(url, interceptor = tokenInterceptor).parsed<AnimeResult>()
        val hasNextPage = position + 36 < parsed.total

        val home = parsed.data.map {
            AnimeSearchResponse(
                it.title,
                "?anime=${it.toJson()}",
                this.name,
                TvType.Anime,
                it.images.poster_tall?.getOrNull(0)?.thirdLast()?.source
                    ?: it.images.poster_tall?.getOrNull(0)?.last()?.source,
                null,
                EnumSet.of(DubStatus.None)
            )
        }

        items.add(HomePageList(request.name, home))
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val soup = app.get(url).document
        return soup.select(".ml-item").map {
            val title = it.select("a .mli-info h2").text()
            val poster = it.select("a img").attr("data-original")
            val href = it.select("a").attr("href")
            val type = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                type,
                poster,
                null,
                EnumSet.of(DubStatus.None)
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Gets the url returned from searching.
        val anime = parseJson<Anime>(url.toHttpUrl().queryParameter("anime") ?: "")
        val info = app.get(
            if (anime.type == "series") {
                "$crApiUrl/cms/series/${anime.id}?locale=en-US"
            } else {
                "$crApiUrl/cms/movie_listings/${anime.id}?locale=en-US"
            },
            interceptor = tokenInterceptor
        ).parsed<AnimeResult>().data.first()

        val title = anime.title
        var desc = anime.description + "\n"
        desc += "\nLanguage:" +
                (
                        if (anime.series_metadata?.subtitle_locales?.any() == true ||
                            anime.movie_metadata?.subtitle_locales?.any() == true ||
                            anime.series_metadata?.is_subbed == true
                        ) {
                            " Sub"
                        } else {
                            ""
                        }
                        ) +
                (
                        if ((anime.series_metadata?.audio_locales?.size ?: 0) > 1 ||
                            anime.movie_metadata?.is_dubbed == true
                        ) {
                            " Dub"
                        } else {
                            ""
                        }
                        )
        desc += "\nMaturity Ratings: " +
                (anime.series_metadata?.maturity_ratings?.joinToString()
                    ?: anime.movie_metadata?.maturity_ratings?.joinToString() ?: "")
        desc += if (anime.series_metadata?.is_simulcast == true) "\nSimulcast" else ""
        desc += "\n\nAudio: " + (anime.series_metadata?.audio_locales?.sortedBy { it.getLocale() }
            ?.joinToString { it.getLocale() } ?: "")
        desc += "\n\nSubs: " + (
                anime.series_metadata?.subtitle_locales?.sortedBy { it.getLocale() }
                    ?.joinToString { it.getLocale() }
                    ?: anime.movie_metadata?.subtitle_locales?.sortedBy { it.getLocale() }
                        ?.joinToString { it.getLocale() } ?: ""
                )
        val description = desc + " ${anime.type}"

        val genres = anime.series_metadata?.genres ?: anime.movie_metadata?.genres ?: emptyList()
        val posterImg = info.images.poster_wide?.getOrNull(0)?.thirdLast()?.source
            ?: info.images.poster_wide?.getOrNull(0)
                ?.last()?.source  // externalOrInternalImg(soup.selectFirst("#mv-info .mvic-thumb img")!!.attr("src"))

        //val episodes = mutableListOf<Episode>()
        val type = if (anime.type?.contains("series") == true) TvType.Anime else TvType.AnimeMovie

        val seasons = app.get(
            if (anime.type == "series") {
                "$crApiUrl/cms/series/${anime.id}/seasons"
            } else {
                "$crApiUrl/cms/movie_listings/${anime.id}/movies"
            },
            interceptor = tokenInterceptor
        ).parsed<SeasonResult>()

        val chunkSize = Runtime.getRuntime().availableProcessors()
        val episodes = if (type == TvType.Anime) {
            seasons.data.sortedBy { it.season_number }.chunked(chunkSize).flatMap { chunk ->
                chunk.parallelMap { seasonData ->
                    runCatching {
                        getEpisodes(seasonData)
                    }.getOrNull()
                }.filterNotNull().flatten()
            }
        } else {
            seasons.data.mapIndexed { index, movie ->
                Episode(
                    EpisodeData(listOf(Pair(movie.id, ""))).toJson(),
                    episode = (index + 1),
                    date = movie.date?.let(::parseDate) ?: 0L
                )
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = posterImg
            addEpisodes(DubStatus.None, episodes)
            showStatus = ShowStatus.Completed
            plot = description
            tags = genres
        }
    }

    private suspend fun getEpisodes(seasonData: SeasonResult.Season): List<Episode> {
        val episodes = app.get(
            "$crApiUrl/cms/seasons/${seasonData.id}/episodes",
            interceptor = tokenInterceptor
        ).parsed<EpisodeResult>()
        return episodes.data.sortedBy { it.episode_number }.mapNotNull EpisodeMap@{ ep ->
            Episode(
                EpisodeData(
                    ep.versions?.map { Pair(it.mediaId, it.audio_locale) }
                        ?: listOf(
                            Pair(
                                ep.streams_link?.substringAfter("videos/")
                                    ?.substringBefore("/streams")
                                    ?: return@EpisodeMap null,
                                ep.audio_locale,
                            ),
                        ),
                ).toJson(),
                name = ep.title,
                episode = ep.episode_number.toInt(),
                season = seasonData.season_number,
                date = ep.airDate?.let(::parseDate) ?: 0L
            )
        }
    }

    private fun String.getLocale(): String {
        return locale.firstOrNull { it.first == this }?.second ?: ""
    }

    // Add new locales to the bottom so it doesn't mess with pref indexes
    private val locale = arrayOf(
        Pair("ar-ME", "Arabic"),
        Pair("ar-SA", "Arabic (Saudi Arabia)"),
        Pair("de-DE", "German"),
        Pair("en-US", "English"),
        Pair("en-IN", "English (India)"),
        Pair("es-419", "Spanish (América Latina)"),
        Pair("es-ES", "Spanish (España)"),
        Pair("es-LA", "Spanish (América Latina)"),
        Pair("fr-FR", "French"),
        Pair("ja-JP", "Japanese"),
        Pair("hi-IN", "Hindi"),
        Pair("it-IT", "Italian"),
        Pair("ko-KR", "Korean"),
        Pair("pt-BR", "Português (Brasil)"),
        Pair("pt-PT", "Português (Portugal)"),
        Pair("pl-PL", "Polish"),
        Pair("ru-RU", "Russian"),
        Pair("tr-TR", "Turkish"),
        Pair("uk-UK", "Ukrainian"),
        Pair("he-IL", "Hebrew"),
        Pair("ro-RO", "Romanian"),
        Pair("sv-SE", "Swedish"),
        Pair("zh-CN", "Chinese (PRC)"),
        Pair("zh-HK", "Chinese (Hong Kong)"),
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeJson = parseJson<EpisodeData>(data);
        if (episodeJson.ids.isEmpty()) throw Exception("No IDs found for episode")

        episodeJson.ids.filter {
            it.second == PREF_AUD_DEFAULT
                    || it.second == PREF_AUD2_DEFAULT
                    || it.second == "ja-JP"
                    || it.second == "en-US"
                    || it.second == ""
        }.parallelMap {
            val (mediaId, audioL) = it
            val streams = app.get(
                "https://beta-api.crunchyroll.com/content/v2/cms/videos/$mediaId/streams",
                interceptor = tokenInterceptor
            ).parsedSafe<CrunchyrollSourcesResponses>()

            listOf(
                "adaptive_hls",
                "vo_adaptive_hls"
            ).map { hls ->
                val name = if (hls == "adaptive_hls") "Crunchyroll" else "Vrv"
                val audio = audioL.getLocale()
                val source = streams?.data?.firstOrNull()
                    ?.let { src -> if (hls == "adaptive_hls") src.adaptive_hls else src.vo_adaptive_hls }
                M3u8Helper.generateM3u8(
                    "$name [$audio]",
                    source?.get("")?.get("url") ?: return@map,
                    "https://static.crunchyroll.com/"
                ).forEach(callback)
            }

            streams?.meta?.subtitles?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        "${sub.key.getLocale()} [ass]",
                        sub.value["url"] ?: return@map null
                    )
                )
            }
        }


        /*callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "url",
                referer = "",
                quality = Qualities.P720.value
            )
        )*/
        return true
    }

    private suspend fun streams(
        media: Pair<String, String>,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        epsTitle: String? = null,
        episode: Int? = null
    ) {
        val (sId, audioL) = media
        val streamsLink =
            app.get(
                "$crUrl/content/v2/cms/seasons/${sId}/episodes",
                interceptor = tokenInterceptor
            ).parsedSafe<CrunchyrollResponses>()?.data?.find {
                it.title.equals(epsTitle, true) || it.slug_title.equals(
                    epsTitle.createSlug(),
                    true
                ) || it.episode_number == episode
            }?.streams_link
        val sources = app.get(fixUrl(streamsLink ?: "", crUrl), interceptor = tokenInterceptor)
            .parsedSafe<CrunchyrollSourcesResponses>()

        listOf(
            "adaptive_hls",
            "vo_adaptive_hls"
        ).map { hls ->
            val name = if (hls == "adaptive_hls") "Crunchyroll" else "Vrv"
            val audio = if (audioL == "en-US") "English Dub" else "Raw"
            val source = sources?.data?.firstOrNull()?.let {
                if (hls == "adaptive_hls")
                    it.adaptive_hls else it.vo_adaptive_hls
            }
            M3u8Helper.generateM3u8(
                "$name [$audio]",
                source?.get("")?.get("url") ?: return@map,
                "https://static.crunchyroll.com/"
            ).forEach(callback)
        }

        sources?.meta?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    "${sub.key.getLocale()} [ass]",
                    sub.value["url"] ?: return@map null
                )
            )
        }
    }

    private suspend fun extractVideo(media: Pair<String, String>): List<Video> {
        val (mediaId, aud) = media
        //val response = app.get("$crUrl/cms/v2{0}/videos/$mediaId/streams?Policy={1}&Signature={2}&Key-Pair-Id={3}")
        //client.newCall(getVideoRequest(mediaId)).execute()
        val streams =
            app.get("$crApiUrl/cms/videos/$mediaId/streams", interceptor = tokenInterceptor)
                .parsed<VideoStreams>()
        //parseJson<VideoStreams>(response.body.string()) //json.decodeFromString<VideoStreams>(response.body.string())

        val subLocale =
            PREF_SUB_DEFAULT.getLocale() //preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!.getLocale()
        val secSubLocale = PREF_SUB2_DEFAULT.getLocale()
        val subsList = runCatching {
            streams.subtitles?.entries?.map { (_, value) ->
                val sub =
                    parseJson<Subtitle>(value.jsonObject.toString()) //json.decodeFromString<Subtitle>(value.jsonObject.toString())
                Track(sub.url, sub.locale.getLocale())
            }?.sortedWith(
                compareBy(
                    { it.lang },
                    { it.lang.contains(subLocale) },
                    { it.lang.contains(secSubLocale) },
                ),
            )
        }.getOrNull() ?: emptyList()

        val audLang = aud.ifBlank { streams.audio_locale } ?: "ja-JP"
        return getStreams(streams, audLang, subsList)
    }

    private fun getStreams(
        streams: VideoStreams,
        audLang: String,
        subsList: List<Track>,
    ): List<Video> {
        return streams.streams?.adaptive_hls?.entries?.parallelMap { (_, value) ->
            val stream =
                parseJson<HlsLinks>(value.jsonObject.toString()) //json.decodeFromString<HlsLinks>(value.jsonObject.toString())
            runCatching {
                val playlist = app.get(
                    stream.url,
                    interceptor = tokenInterceptor
                ) //client.newCall(GET(stream.url)).execute()
                if (playlist.code != 200) return@parallelMap null
                playlist.body.string().substringAfter("#EXT-X-STREAM-INF:")
                    .split("#EXT-X-STREAM-INF:").map {
                        val hardsub = stream.hardsub_locale.let { hs ->
                            if (hs.isNotBlank()) " - HardSub: $hs" else ""
                        }

                        val quality = it.substringAfter("RESOLUTION=")
                            .split(",")[0].split("\n")[0].substringAfter("x") + "p"

                        val qualityTitle = it.substringAfter("RESOLUTION=")
                            .split(",")[0].split("\n")[0].substringAfter("x") +
                                "p - Aud: ${audLang.getLocale()}$hardsub"

                        val videoUrl = it.substringAfter("\n").substringBefore("\n")

                        try {
                            Video(
                                videoUrl,
                                qualityTitle,
                                videoUrl,
                                subtitleTracks = if (hardsub.isNotBlank()) emptyList() else subsList,
                            )
                        } catch (_: Error) {
                            Video(videoUrl, qualityTitle, videoUrl)
                        }
                    }
            }.getOrNull()
        }?.filterNotNull()?.flatten() ?: emptyList()
    }

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QLT_KEY = "preferred_quality"
        private const val PREF_QLT_TITLE = "Preferred quality"
        private const val PREF_QLT_DEFAULT = "1080p"
        private val PREF_QLT_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
        private val PREF_QLT_VALUES = PREF_QLT_ENTRIES

        private const val PREF_AUD_KEY = "preferred_audio"
        private const val PREF_AUD_TITLE = "Preferred Audio Language"
        private const val PREF_AUD_DEFAULT = "es-419"
        private const val PREF_AUD2_DEFAULT = "es-LA"

        private const val PREF_SUB_KEY = "preferred_sub"
        private const val PREF_SUB_TITLE = "Preferred Sub Language"
        private const val PREF_SUB_DEFAULT = "es-419"
        private const val PREF_SUB2_DEFAULT = "es-LA"

        private const val PREF_SUB_TYPE_KEY = "preferred_sub_type"
        private const val PREF_SUB_TYPE_TITLE = "Preferred Sub Type"
        private const val PREF_SUB_TYPE_DEFAULT = "soft"
        private val PREF_SUB_TYPE_ENTRIES = arrayOf("Softsub", "Hardsub")
        private val PREF_SUB_TYPE_VALUES = arrayOf("soft", "hard")

        private const val PREF_USE_LOCAL_TOKEN_KEY = "preferred_local_Token"
        private const val PREF_USE_LOCAL_TOKEN_TITLE = "Use Local Token (Don't Spam this please!)"
    }

    private fun getCrunchyrollToken(): Map<String, String> {
        val client = app.baseClient.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("cr-unblocker.us.to", 1080)))
            .build()

        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication("crunblocker", "crunblocker".toCharArray())
            }
        })

        val getRequest = Request.Builder()
            .url("https://raw.githubusercontent.com/Samfun75/File-host/main/aniyomi/refreshToken.txt")
            .build()
        val refreshTokenResp = client.newCall(getRequest).execute()
        val refreshToken = refreshTokenResp.body.string().replace("[\n\r]".toRegex(), "")

        val request = requestCreator(
            method = "POST",
            url = "$crUrl/auth/v1/token",
            headers = mapOf(
                "User-Agent" to "Crunchyroll/3.26.1 Android/11 okhttp/4.9.2",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Authorization" to "Basic a3ZvcGlzdXZ6Yy0teG96Y21kMXk6R21JSTExenVPVnRnTjdlSWZrSlpibzVuLTRHTlZ0cU8="
            ),
            data = mapOf(
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
                "scope" to "offline_access"
            )
        )

        val response =
            tryParseJson<CrunchyrollToken>(client.newCall(request).execute().body.string())
        return mapOf("Authorization" to "${response?.tokenType} ${response?.accessToken}")
    }

    private fun String?.createSlug(): String? {
        return this?.replace(Regex("[^\\w\\s-]"), "")
            ?.replace(" ", "-")
            ?.replace(Regex("( – )|( -)|(- )|(--)"), "-")
            ?.lowercase()
    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http")) {
            return url
        }
        if (url.isEmpty()) {
            return ""
        }

        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) {
            return "https:$url"
        } else {
            if (url.startsWith('/')) {
                return domain + url
            }
            return "$domain/$url"
        }
    }
}