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

class FanPelisProvider : MainAPI() {

    override var mainUrl = "https://fanpelis.la"
    override var name = "FanPelis"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/movies-hd/page/", "Peliculas"),
        Pair("$mainUrl/series/page/", "Series")
    )


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get(request.data + page).document

        val hasNextPage = soup.select(".pagination li.active ~ li").any()
        val home = soup.select(".ml-item").map {
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
        val soup = app.get(url).document
        val title = soup.selectFirst(".mvic-desc h3[itemprop=\"name\"]")?.text() ?: ""
        val description = soup.selectFirst(".mvic-desc .desc p")!!.text().removeSurrounding("\"")
        val genres = soup.select(".mvic-info .mvici-left p a[rel=\"category tag\"]").map { it.text().trim() }
        val posterImg = externalOrInternalImg(soup.selectFirst("#mv-info .mvic-thumb img")!!.attr("src"))
        val episodes = mutableListOf<Episode>()
        val type = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
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
        }

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = posterImg
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = ShowStatus.Completed
            plot = description
            tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select(".movieplay iframe").apmap { iframe ->
            val url = fixUrl(iframe.attr("src").ifEmpty { iframe.attr("data-src") })
            loadExtractor(url, data, subtitleCallback, callback)
        }
        return true
    }
}