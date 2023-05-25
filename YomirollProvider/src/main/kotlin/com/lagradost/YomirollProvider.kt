package com.lagradost

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.*

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

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        Pair("$crApiUrl/discover/browse?{start}n=36&sort_by=popularity&locale=en-US", "Animes populares"),
        Pair("$crApiUrl/discover/browse?{start}n=36&sort_by=newly_added&locale=en-US", "Nuevos animes"),
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
                LinkData(it.id, it.type!!).toJson(),
                this.name,
                TvType.Anime,
                it.images.poster_tall?.getOrNull(0)?.thirdLast()?.source ?: it.images.poster_tall?.getOrNull(0)?.last()?.source,
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

    private fun externalOrInternalImg(url: String): String {
        return if (url.contains("https")) url else "$mainUrl/$url"
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Gets the url returned from searching.
        val mediaId = parseJson<LinkData>("{" + url.substringAfter("{").substringBefore("}") + "}");

        val soup = app.get(
            if (mediaId.media_type == "series") {
                "$crApiUrl/cms/series/${mediaId.id}?locale=en-US"
            } else {
                "$crApiUrl/cms/movie_listings/${mediaId.id}?locale=en-US"
            }
        ).parsed<AnimeResult>()

        val info = soup.data.first()

        val title = info.title
        var desc = info.description + "\n"
        desc += "\nLanguage:" +
                (
                        if (info.series_metadata?.subtitle_locales?.any() == true ||
                            info.movie_metadata?.subtitle_locales?.any() == true ||
                            info.series_metadata?.is_subbed == true
                        ) {
                            " Sub"
                        } else {
                            ""
                        }
                        ) +
                (
                        if ((info.series_metadata?.audio_locales?.size ?: 0) > 1 ||
                            info.movie_metadata?.is_dubbed == true
                        ) {
                            " Dub"
                        } else {
                            ""
                        }
                        )
        desc += "\nMaturity Ratings: " +
                ( info.series_metadata?.maturity_ratings?.joinToString()
                    ?: info.movie_metadata?.maturity_ratings?.joinToString() ?: "")
        desc += if (info.series_metadata?.is_simulcast == true) "\nSimulcast" else ""
        desc += "\n\nAudio: " + (
                info.series_metadata?.audio_locales?.sortedBy { it.getLocale() }
                    ?.joinToString { it.getLocale() } ?: ""
                )
        desc += "\n\nSubs: " + (
                info.series_metadata?.subtitle_locales?.sortedBy { it.getLocale() }
                    ?.joinToString { it.getLocale() }
                    ?: info.movie_metadata?.subtitle_locales?.sortedBy { it.getLocale() }
                        ?.joinToString { it.getLocale() } ?: ""
                )
        val description = desc
        val genres = info.series_metadata?.genres ?: info.movie_metadata?.genres ?: emptyList() //  soup.select(".mvic-info .mvici-left p a[rel=\"category tag\"]").map { it.text().trim() }
        val posterImg = info.images.poster_tall?.getOrNull(0)?.thirdLast()?.source ?: info.images.poster_tall?.getOrNull(0)?.last()?.source  // externalOrInternalImg(soup.selectFirst("#mv-info .mvic-thumb img")!!.attr("src"))

        val episodes = mutableListOf<Episode>()
        val type = if (info.type?.contains("series") == true) TvType.Anime else TvType.AnimeMovie
        /*val type = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
        if (type == TvType.Movie) {
             episodes.add(Episode(url))
        } else {
            soup.select("#seasons .tvseason").mapIndexed { idxSeason, season ->
                val noSeason = try { getNumberFromString(season.selectFirst(".les-title strong")?.text() ?: "").toInt() } catch (_: Exception) { idxSeason + 1 }
                season.select(".les-content a").mapIndexed { idxEpisode, ep ->
                    val noEpisode = try { getNumberFromString(ep.text()).toInt() } catch (_: Exception) { idxEpisode + 1 }
                    episodes.add(Episode(ep.attr("href"), episode = noEpisode, season = noSeason))
                }
            }
        }*/

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = posterImg
            addEpisodes(DubStatus.None, episodes)
            showStatus = ShowStatus.Completed
            plot = description
            tags = genres
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
        app.get(data).document.select(".movieplay iframe").apmap { iframe ->
            var embedUrl = fixUrl(iframe.attr("src").ifEmpty { iframe.attr("data-src") })
            loadExtractor(embedUrl, data, subtitleCallback, callback)

            if (embedUrl.contains("sbembed.com") || embedUrl.contains("sbembed1.com") || embedUrl.contains("sbplay.org") ||
                embedUrl.contains("sbvideo.net") || embedUrl.contains("streamsb.net") || embedUrl.contains("sbplay.one") ||
                embedUrl.contains("cloudemb.com") || embedUrl.contains("playersb.com") || embedUrl.contains("tubesb.com") ||
                embedUrl.contains("sbplay1.com") || embedUrl.contains("embedsb.com") || embedUrl.contains("watchsb.com") ||
                embedUrl.contains("sbplay2.com") || embedUrl.contains("japopav.tv") || embedUrl.contains("viewsb.com") ||
                embedUrl.contains("sbfast") || embedUrl.contains("sbfull.com") || embedUrl.contains("javplaya.com") ||
                embedUrl.contains("ssbstream.net") || embedUrl.contains("p1ayerjavseen.com") || embedUrl.contains("sbthe.com") ||
                embedUrl.contains("vidmovie.xyz") || embedUrl.contains("sbspeed.com") || embedUrl.contains("streamsss.net") ||
                embedUrl.contains("sblanh.com") || embedUrl.contains("sbbrisk.com") || embedUrl.contains("lvturbo.com")
            ) {
                embedUrl = "https://sbfull.com/e/${embedUrl.substringAfter("/e/")}"
            }
            if (embedUrl.contains("streamlare")) {
                try {
                    val id = embedUrl.substringAfter("/e/").substringBefore("?poster")
                    app.post("https://slwatch.co/api/video/stream/get?id=$id").okhttpResponse.body.toString().let {
                        val videoUrl = it.substringAfter("file\":\"").substringBefore("\"").ifEmpty {
                            it.substringAfter("file=\"").substringBefore("\"")
                        }.trim()
                        val type = if (videoUrl.contains(".m3u8")) "HSL" else "MP4"
                        val headers = Headers.Builder()
                            .add("authority", videoUrl.substringBefore("/hls").substringBefore("/mp4"))
                            .add("origin", "https://slwatch.co")
                            .add("referer", "https://slwatch.co/e/" + embedUrl.substringAfter("/e/"))
                            .add(
                                "sec-ch-ua",
                                "\"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"108\", \"Google Chrome\";v=\"108\"",
                            )
                            .add("sec-ch-ua-mobile", "?0")
                            .add("sec-ch-ua-platform", "\"Windows\"")
                            .add("sec-fetch-dest", "empty")
                            .add("sec-fetch-mode", "cors")
                            .add("sec-fetch-site", "cross-site")
                            .add(
                                "user-agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/),108.0.0.0 Safari/537.36",
                            )
                            .add("Accept-Encoding", "gzip, deflate, br")
                            .add("accept", "*/*")
                            .add(
                                "accept-language",
                                "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7,zh-TW;q=0.6,zh-CN;q=0.5,zh;q=0.4",
                            )
                            .build()
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                this.name,
                                videoUrl,
                                referer = "https://slwatch.co/e/" + embedUrl.substringAfter("/e/"),
                                quality = Qualities.Unknown.value,
                                isM3u8 = type == "HSL",
                                headers = headers.toMap()
                            )
                        )
                    }
                }catch (_:Exception) {}
            }
        }
        return true
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
        private const val PREF_AUD_DEFAULT = "en-US"

        private const val PREF_SUB_KEY = "preferred_sub"
        private const val PREF_SUB_TITLE = "Preferred Sub Language"
        private const val PREF_SUB_DEFAULT = "en-US"

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

        val response = tryParseJson<CrunchyrollToken>(client.newCall(request).execute().body.string())
        return mapOf("Authorization" to "${response?.tokenType} ${response?.accessToken}")
    }
}