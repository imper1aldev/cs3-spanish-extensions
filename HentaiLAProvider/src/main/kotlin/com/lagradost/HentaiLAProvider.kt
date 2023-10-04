package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.toWebResourceResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import java.util.*


class HentaiLAProvider : MainAPI() {

    override var mainUrl = "https://hentaila.tv"
    override var name = "HentaiLA"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/page/%d/?m_orderby=latest", "Recientes"),
        Pair("$mainUrl/page/%d/?m_orderby=rating", "Populares")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get(String.format(request.data, page)).document
        val hasNextPage = soup.select(".wp-pagenavi a.nextpostslink").any()

        val home = soup.select(".item_card").map {
            val title = it.selectFirst("a .card__title")!!.text()
            val poster = it.selectFirst("a img")!!.attr("abs:src")
            AnimeSearchResponse(
                title,
                fixUrl(it.selectFirst("a")!!.attr("abs:href")),
                this.name,
                TvType.Anime,
                poster,
                null,
                EnumSet.of(DubStatus.Subbed)
            )
        }

        items.add(HomePageList(request.name, home))
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val soup = app.get("$mainUrl/?s=$query").document
        return soup.select(".item_card").map {
            val title = it.selectFirst("a .card__title")!!.text()
            val poster = it.selectFirst("a img")!!.attr("abs:src")
            AnimeSearchResponse(
                    title,
                    fixUrl(it.selectFirst("a")!!.attr("abs:href")),
                    this.name,
                    TvType.Anime,
                    poster,
                    null,
                    EnumSet.of(DubStatus.Subbed)
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Gets the url returned from searching.
        val soup = app.get(url).document
        val title = soup.selectFirst(".hentai_cover img")!!.attr("alt").substringBefore("cover").trim()
        val description = soup.selectFirst("div.vraven_expand > div.vraven_text.single > p")!!.text()
        var genres = listOf<String>()
        var year = 0
        soup.select(".single_data").forEach {
            if (it.select("h5").text().contains("Genre", true)) {
                genres = it.select(".list a").map { it.text() }
            }
            if (it.select("h5").text().contains("Release", true)) {
                year = it.selectFirst("div a")?.text()?.toInt() ?: 0
            }
        }

        val episodes = soup.select(".hentai__episodes .hentai__chapter").map {
            val href = it.select("a").attr("abs:href")
            val poster = it.select("a img").attr("abs:src")
            val noEpisode = it.select("a").text().filter { it.isDigit() }.toFloat()

            Episode(href, episode = noEpisode.toInt(), posterUrl = poster)
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = soup.selectFirst(".hentai_cover img")!!.attr("abs:src")
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = ShowStatus.Completed
            plot = description
            tags = genres
            year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframe = document.select("iframe").attr("abs:src")
        val videoDocument = app.get(iframe).document

        videoDocument.select("script:containsData(const loaded = async ())").map { script ->
            val action = script.data().substringAfter("'action', '").substringBefore("'")
            val a = script.data().substringAfter("'a', '").substringBefore("'")
            val b = script.data().substringAfter("'b', '").substringBefore("'")

            val formBody = FormBody.Builder()
                    .add("action", "zarat_get_data_player_ajax")
                    .add("a", a)
                    .add("b", b)
                    .build()

            val request = Request.Builder()
                    .url("https://hentaila.tv/wp-content/plugins/player-logic/api.php")
                    .post(formBody)
                    .header("authority", "hentaila.tv")
                    .header("origin", "https://hentaila.tv")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

            app.baseClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    /*val hlsJson = app.post(
                            urlRequest,
                            headers = request.headers.toMap(),
                            data = mapOf(
                                    Pair("action", action),
                                    Pair("a", a),
                                    Pair("b", b)
                            ),
                            requestBody = formBody
                    )*/
                    val bodyText = response.body.toString().substringAfter("<body>").substringBefore("</body>").trim()
                    callback.invoke(
                            ExtractorLink(
                                    this.name,
                                    "Prueba Json",
                                    bodyText,
                                    referer = "",
                                    quality = Qualities.Unknown.value
                            )
                    )

                    parseJson<HlsJson>(bodyText).data.sources.apmap { hls ->
                        generateM3u8(
                                this.name,
                                hls.src?.replace("&amp;", "&") ?: "",
                                "",
                                headers = mapOf(
                                        "Accept" to "*/*",
                                        "Accept-Encoding" to "gzip, deflate, br",
                                        "Accept-Language" to "en-US,en;q=0.9",
                                        "Connection" to "keep-alive",
                                        "Host" to "master-es.cyou",
                                        "Origin" to "https://hentaila.tv",
                                        "Sec-Fetch-Dest" to "empty",
                                        "Sec-Fetch-Mode" to "cors",
                                        "Sec-Fetch-Site" to "cross-site",
                                        "User-Agent" to USER_AGENT,
                                        "sec-ch-ua" to "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Opera\";v=\"102\"",
                                        "sec-ch-ua-mobile" to "?0"
                                )
                        ).forEach(callback)
                    }
                }
            }
        }
        return true
    }

    data class HlsJson(
            val status: Boolean,
            val data: Data = Data(),
    )

    data class Data(
            val image: String? = null,
            val mosaic: String? = null,
            val sources: List<Source> = arrayListOf(),
    )

    data class Source(
            val src: String? = null,
            val type: String? = null,
            val label: String? = null,
    )
}