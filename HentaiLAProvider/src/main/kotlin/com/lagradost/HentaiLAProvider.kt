package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("script").apmap { script ->
            if (script.data().contains("window.hola_player({")) {
                script.data().substringAfter("sources: [{src: \"").substringBefore("\",").let { url ->
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            url,
                            referer = "",
                            quality = Qualities.P720.value
                        )
                    )
                }
            }
        }
        return true
    }
}