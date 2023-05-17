package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*

class EnNovelasProvider : MainAPI() {

    override var mainUrl = "https://www.zonevipz.com"
    override var name = "EnNovelas"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/just_added.html", "Últimos episodios"),
        Pair("$mainUrl/?op=categories_all&per_page=60&page=", "Novelas Populares")
    )

    private fun changeUrlFormat(link: String): String {
        val novel = link.substringAfter("/category/").replace("+", "%20")
        return "$mainUrl/?cat_name=$novel&op=search&per_page=100000"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()

        val soup = app.get(request.data + page).document
        val hasNextPage = soup.select("#container section div.section-content div.paging a:last-of-type").any()
        val home: List<AnimeSearchResponse>
        if (request.data.contains("just_added")) {
            home = soup.select(".videobox").map {
                val title = it.selectFirst("a:nth-child(2)")?.text() ?: ""
                val poster = it.select("a.video200 div").attr("style")
                    .substringAfter("background-image: url(\"").substringBefore("\")")
                AnimeSearchResponse(
                    title,
                    fixUrl(changeUrlFormat(it.select("a.video200").attr("href"))),
                    this.name,
                    TvType.TvSeries,
                    poster,
                    null,
                    EnumSet.of(DubStatus.Dubbed)
                )
            }
        }
        else
        {
            home = soup.select("#container section.search-videos div.section-content div.row div div.col-xs-6 div.video-post").map {
                val title = it.selectFirst("a p")?.text() ?: ""
                val poster = it.select("a div.thumb").attr("style")
                    .substringAfter("background-image:url(").substringBefore(")")
                AnimeSearchResponse(
                    title,
                    fixUrl(changeUrlFormat(it.select("a").attr("href"))),
                    this.name,
                    TvType.TvSeries,
                    poster,
                    null,
                    EnumSet.of(DubStatus.Dubbed)
                )
            }
        }

        items.add(HomePageList(request.name, home))
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?name=$query&op=categories_all&per_page=100000"
        val document = app.get(url).document
        return document.select("#container section.search-videos div.section-content div.row div div.col-xs-6 div.video-post").map {
            val title = it.selectFirst("a p")?.text() ?: ""
            val poster = it.select("a div.thumb").attr("style")
                .substringAfter("background-image:url(").substringBefore(")")
            AnimeSearchResponse(
                title,
                fixUrl(changeUrlFormat(it.select("a").attr("href"))),
                this.name,
                TvType.TvSeries,
                poster,
                null,
                EnumSet.of(DubStatus.Dubbed)
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Gets the url returned from searching.
        val soup = app.get(url).document
        val title = soup.selectFirst("#inwg h3 span.first-word")!!.text()
        val description = soup.selectFirst("#inwg")!!.ownText()
        val episodes = soup.select("#col3 div.videobox").map { element ->
            val noEpisode = getNumberFromEpsString(
                element.selectFirst("a:nth-child(2)")!!.text().substringAfter("Cap")
                    .substringBefore("FIN").substringBefore("fin"),
            )
            val poster = element.select("a.video200 div").attr("style")
                .substringAfter("background-image: url(\"").substringBefore("\")")
            val href = element.selectFirst("a.video200")!!.attr("href")

            Episode(href, episode = noEpisode.toInt(), posterUrl = poster)
        }

        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            posterUrl = null
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = ShowStatus.Completed
            plot = description
        }
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("script").apmap { script ->
            if (script.data().contains("window.hola_player({")) {
                val url = script.data().substringAfter("sources: [{src: \"").substringBefore("\",")
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }
        return true
    }
}