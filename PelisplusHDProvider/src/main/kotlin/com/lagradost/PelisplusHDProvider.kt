package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class PelisplusHDProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.nz"
    override var name = "PelisplusHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("serie")) TvType.TvSeries
            else if (t.contains("pelicula")) TvType.Movie
            else TvType.Anime
        }
    }

    override val mainPage = mainPageOf(
        Pair("$mainUrl/peliculas?page=", "Películas"),
        Pair("$mainUrl/series?page=", "Series"),
        Pair("$mainUrl/generos/dorama?page=", "Doramas"),
        Pair("$mainUrl/animes?page=", "Animes")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get(request.data + page).document
        val hasNextPage = soup.select("a[rel=\"next\"]").any()
        items.add(
            HomePageList(
                request.name,
                soup.select("div.Posters a.Posters-link").map {
                    it.toSearchResult()
                }
            )
        )
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items, hasNextPage)
    }



    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("a div.listing-content p")!!.text()
        val posterUrl = this.selectFirst("a img")!!.attr("src").replace("/w154/", "/w200/")
        val link = fixUrl(this.select("a").attr("href"))
        return when (getType(link)) {
            TvType.Movie -> {
                MovieSearchResponse(
                    title,
                    link,
                    name,
                    TvType.Movie,
                    posterUrl,
                    null
                )
            }
            TvType.TvSeries -> {
                TvSeriesSearchResponse(
                    title,
                    link,
                    name,
                    TvType.TvSeries,
                    posterUrl,
                    null,
                    null
                )
            }
            else -> {
                AnimeSearchResponse(
                    title,
                    link,
                    name,
                    TvType.Anime,
                    posterUrl,
                    null,
                    null
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        //https://pelisplushd.nz/search?s=love&page=2
        val url = "$mainUrl/search?s=${query}"
        val document = app.get(url).document
        return document.select("div.Posters a.Posters-link").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document
        val episodes = ArrayList<Episode>()
        val tvType = getType(url)

        if (tvType == TvType.Movie) {
            episodes.add(Episode(url,"Película"))
        }
        else {
            soup.select("div.tab-content div a").forEachIndexed { index, element ->
                val epUrl = element.attr("href")
                val parentId = element.parent()?.attr("id")
                val epName = try {
                    element.text().substringAfter(":").trim()
                }
                catch (e:Exception){
                    "Capítulo ${index + 1}"
                }
                val season = try {
                    url.substringAfter("/temporada/").substringBefore("/").filter { it.isDigit() }.toInt()
                }
                catch (e:Exception){
                    element.text().substringBefore("-").filter { it.isDigit() }.toInt()
                }
                val noEpisode = try {
                    element.text().substringAfter(":").filter { it.isDigit() }.toInt()
                }
                catch (e:Exception){
                    index + 1
                }
                episodes.add(Episode(epUrl, epName, season, noEpisode))
            }
        }

        val title = soup.selectFirst("h1.m-b-5")!!.text()
        val otrTitle = soup.selectFirst("div.m-v-30 div:nth-child(3) div.text-large")?.text()
        val description = soup.selectFirst("div.col-sm-4 div.text-large")!!.ownText()
        val poster = soup.selectFirst("div.card-body div.row div.col-sm-3 img.img-fluid")!!
            .attr("src").replace("/w154/", "/w500/")
        val year = soup.selectFirst("div.p-v-20.p-r-15.text-center > span")!!.text().toIntOrNull()
        val tagsEp = soup.select("div.p-v-20.p-h-15.text-center a span").map { it.text().trim() }
        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    poster,
                    year,
                    description,
                    null,
                    null,
                    tagsEp,
                )
            }
            TvType.Movie -> {
                MovieLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    url,
                    poster,
                    year,
                    description,
                    null,
                    tagsEp,
                )
            }
            else -> {
                return newAnimeLoadResponse(title ?: "", url, tvType) {
                    japName = null
                    engName = otrTitle
                    name = title
                    posterUrl = poster
                    this.year = null
                    addEpisodes(DubStatus.None, episodes)
                    plot = description
                    tags = tagsEp
                    showStatus = null
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val content = document.selectFirst("script:containsData(video[1] = )")!!.data()
        val apiUrl = content.substringAfter("video[1] = '", "").substringBefore("';", "")
        val alternativeServers = document.select("ul.TbVideoNv.nav.nav-tabs li:not(:first-child)")
        if (apiUrl.isNotEmpty()) {
            val domainRegex = Regex("^(?:https?:\\/\\/)?(?:[^@\\/\\n]+@)?(?:www\\.)?([^:\\/?\\n]+)")
            val domainUrl = domainRegex.findAll(apiUrl).firstOrNull()?.value ?: ""

            val apiResponse = app.get(apiUrl).document
            val encryptedList = apiResponse.select("#PlayerDisplay div[class*=\"OptionsLangDisp\"] div[class*=\"ODDIV\"] div[class*=\"OD\"] li[data-r]")
            val decryptedList = apiResponse.select("#PlayerDisplay div[class*=\"OptionsLangDisp\"] div[class*=\"ODDIV\"] div[class*=\"OD\"] li:not([data-r])")
            encryptedList.forEach {
                var url = base64Decode(it.attr("data-r"))
                val serverName = it.select("span").text()
                when (serverName.lowercase()) {
                    "sbfast" -> { url = "https://sbfull.com/e/${url.substringAfter("/e/")}" }
                }
                loadExtractor(url, data, subtitleCallback, callback)
            }
            decryptedList.forEach {
                val serverName = it.select("span").text()
                val url = it.attr("onclick")
                    .substringAfter("go_to_player('")
                    .substringBefore("?cover_url=")
                    .substringBefore("')")
                    .substringBefore("',")
                    .substringBefore("?poster")
                    .substringBefore("#poster=")

                val decryptedUrls = fetchUrls(url);
                if (decryptedUrls.any()) {
                    decryptedUrls.map { decUrl ->
                        var realUrl = decUrl
                        when (serverName.lowercase()) {
                            "sbfast" -> { realUrl = "https://sbfull.com/e/${realUrl.substringAfter("/e/")}" }
                        }
                        loadExtractor(realUrl, data, subtitleCallback, callback)
                    }
                }
                else {
                    val apiPageSoup = app.get("$domainUrl/player/?id=$url").document
                    var realUrl = apiPageSoup.selectFirst("iframe")?.attr("src") ?: ""
                    when (serverName.lowercase()) {
                        "sbfast" -> { realUrl = "https://sbfull.com/e/${realUrl.substringAfter("/e/")}" }
                    }
                    if (realUrl.isNotEmpty()) loadExtractor(realUrl, data, subtitleCallback, callback)
                }
            }
        }
        // verifier for old series
        if (!apiUrl.contains("/video/") || alternativeServers.any()) {
            document.select("ul.TbVideoNv.nav.nav-tabs li").forEach { id ->
                val serverName = id.select("a").text()
                val serverId = id.attr("data-id")
                var serverUrl = content.substringAfter("video[$serverId] = '", "").substringBefore("';", "")
                if (serverUrl.contains("api.mycdn.moe")) {
                    val urlId = serverUrl.substringAfter("id=")
                    when (serverName.lowercase()) {
                        "sbfast" -> { serverUrl = "https://sbfull.com/e/$urlId" }
                        "plusto" -> { serverUrl = "https://owodeuwu.xyz/v/$urlId" }
                        "doodstream" -> { serverUrl = "https://dood.to/e/$urlId" }
                        "upload" -> { serverUrl = "https://uqload.com/embed-$urlId.html" }
                    }
                }
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
