package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.util.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

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

            val playerHeaders = mapOf(
                    "authority" to "hentaila.tv",
                    "accept" to "*/*",
                    "accept-language" to "en-US,en;q=0.9",
                    "content-type" to "multipart/form-data; boundary=----WebKitFormBoundarybab9W9OlAvbOUs9J",
                    "origin" to "https://hentaila.tv",
                    "user-agent" to USER_AGENT,
                    "sec-ch-ua" to "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Opera\";v=\"102\"",
                    "sec-ch-ua-mobile" to "?0",
                    "sec-fetch-dest" to "empty",
                    "sec-fetch-mode" to "cors",
                    "sec-fetch-site" to "same-origin",
            )
            val urlRequest = "https://hentaila.tv/wp-content/plugins/player-logic/api.php"
            val hlsJson = app.post(urlRequest, headers = playerHeaders, data = mapOf("action" to action, "a" to a, "b" to b)).parsed<HlsJson>()
            hlsJson.data.sources.filter { it.src!!.isEmpty() }.apmap { hls ->
                generateM3u8(
                        this.name,
                        hls.src ?: "",
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