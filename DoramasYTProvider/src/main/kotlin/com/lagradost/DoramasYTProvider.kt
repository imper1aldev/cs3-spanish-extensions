package com.lagradost

import com.lagradost.cloudstream3.*
//import com.lagradost.cloudstream3.extractors.FEmbed
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*


class DoramasYTProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.Movie
            else TvType.TvSeries
        }
        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
    }

    override var mainUrl = "https://doramasyt.com"
    override var name = "DoramasYT"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        Pair(mainUrl, "Capítulos actualizados"),
        Pair("$mainUrl/emision", "En emisión"),
        Pair("$mainUrl/doramas?categoria=pelicula&genero=false&fecha=false&letra=false&p=", "Peliculas"),
        Pair("$mainUrl/doramas?p=", "Doramas"),
        Pair("$mainUrl/doramas?categoria=live-action&genero=false&fecha=false&letra=false?p=", "Live Action"),
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        var hasNextPage = false;
        if (request.data == mainUrl) {
            items.add(
                HomePageList(
                    "Capítulos actualizados",
                    app.get(mainUrl, timeout = 120).document.select(".chaps").map {
                        val title = it.selectFirst("a p.my-3")!!.text()
                        val poster = it.selectFirst("a .chapter img")!!.attr("src")
                        val epRegex = Regex("episodio-(\\d+)")
                        val url = it.selectFirst("a")!!.attr("href").replace("ver/", "dorama/")
                            .replace(epRegex, "sub-espanol")
                        val epNum = it.selectFirst("a .chapter h3")!!.text().toIntOrNull()
                        newAnimeSearchResponse(title,url) {
                            this.posterUrl = fixUrl(poster)
                            addDubStatus(getDubStatus(title), epNum)
                        }
                    }
                )
            )
        }
        else {
            try {
                val url = if(request.data.contains("/emision")) request.data else request.data + page
                val soup = app.get(url, timeout = 120).document
                hasNextPage = soup.select("ul.pagination li a[rel=\"next\"]").any()
                val homePage = HomePageList(
                    request.name,
                    soup.select("div.col-lg-2.col-md-4.col-6 div.animes").mapNotNull {
                        val title = it.selectFirst("div.animedtls p")?.text() ?: ""
                        val poster = if (request.data.contains("/emision")) {
                            it.selectFirst("a img")?.attr("src")
                        }
                        else {
                            it.selectFirst("div.anithumb a img")?.attr("src")
                        }

                        val link = if (request.data.contains("/emision")) {
                            it.selectFirst("a")?.attr("href")
                        }
                        else {
                            it.selectFirst("div.anithumb a")?.attr("href")
                        }

                        newAnimeSearchResponse(
                            title,
                            fixUrl(link ?: "")
                        ) {
                            this.posterUrl = fixUrl(poster ?: "")
                            addDubStatus(getDubStatus(title))
                        }
                    }
                )
                items.add(homePage)
            }
            catch (e: Exception){
                e.printStackTrace()
            }
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/buscar?q=$query", timeout = 120).document.select(".col-6").map {
            val title = it.selectFirst(".animedtls p")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst(".animes img")!!.attr("src")
            AnimeSearchResponse(
                title,
                href,
                this.name,
                TvType.Anime,
                image,
                null,
                if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                    DubStatus.Dubbed
                ) else EnumSet.of(DubStatus.Subbed),
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document
        val poster = doc.selectFirst("div.flimimg img.img1")!!.attr("src")
        val title = doc.selectFirst("h1")!!.text()
        val type = doc.selectFirst("h4")!!.text()
        val description = doc.selectFirst("p.textComplete")!!.text().replace("Ver menos", "")
        val genres = doc.select(".nobel a").map { it.text() }
        val status = when (doc.selectFirst(".state h6")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val episodes = doc.select(".heromain .col-item").map {
            val name = it.selectFirst(".dtlsflim p")!!.text()
            val link = it.selectFirst("a")!!.attr("href")
            val epThumb = it.selectFirst(".flimimg img.img1")!!.attr("src")
            Episode(link, name, posterUrl = epThumb)
        }
        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
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
        app.get(data).document.select("div.playother p").apmap {
            val encodedurl = it.attr("data-player")
            val urlDecoded = base64Decode(encodedurl)
            val url = urlDecoded.substringAfter("?url=")
            if (url.startsWith("https://www.fembed.com")) {
                val extractor = FEmbed()
                extractor.getUrl(url).forEach { link ->
                    callback.invoke(link)
                }
            } else {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}