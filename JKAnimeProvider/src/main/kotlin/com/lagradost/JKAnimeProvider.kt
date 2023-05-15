package com.lagradost


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*


class JKAnimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }
    }

    override var mainUrl = "https://jkanime.net"
    override var name = "JKAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        Pair(mainUrl, "Últimos episodios"),
        Pair("$mainUrl/directorio/%d/emision/desc/", "En emisión"),
        Pair("$mainUrl/directorio/%d/animes/desc/", "Animes"),
        Pair("$mainUrl/directorio/%d/peliculas/desc/", "Películas"),
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        var hasNextPage = false
        if (request.data == mainUrl) {
            items.add(
                HomePageList(
                    request.name,
                    app.get(request.data).document.select(".listadoanime-home a.bloqq").map {
                        val title = it.selectFirst("h5")?.text()
                        val dubstat = if (title!!.contains("Latino") || title.contains("Castellano"))
                            DubStatus.Dubbed else DubStatus.Subbed
                        val poster =
                            it.selectFirst(".anime__sidebar__comment__item__pic img")?.attr("src") ?: ""
                        val epRegex = Regex("/(\\d+)/|/especial/|/ova/")
                        val url = it.attr("href").replace(epRegex, "")
                        val epNum =
                            it.selectFirst("h6")?.text()?.replace("Episodio ", "")?.toIntOrNull()
                        newAnimeSearchResponse(title, url) {
                            this.posterUrl = poster
                            addDubStatus(dubstat, epNum)
                        }
                    })
            )
        }
        else {
            val url = String.format(request.data, page)
            val soup = app.get(url).document
            hasNextPage = soup.select("div.navigation a.nav-next").any()
            val home = soup.select(".g-0").map {
                val title = it.selectFirst("h5 a")?.text()
                val poster = it.selectFirst("img")?.attr("src") ?: ""
                AnimeSearchResponse(
                    title!!,
                    fixUrl(it.selectFirst("a")?.attr("href") ?: ""),
                    this.name,
                    TvType.Anime,
                    fixUrl(poster),
                    null,
                    if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                        DubStatus.Dubbed
                    ) else EnumSet.of(DubStatus.Subbed),
                )
            }
            items.add(HomePageList(request.name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items, hasNextPage)
    }

    data class MainSearch(
        @JsonProperty("animes") val animes: List<Animes>,
        @JsonProperty("anime_types") val animeTypes: AnimeTypes
    )

    data class Animes(
        @JsonProperty("id") val id: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("image") val image: String,
        @JsonProperty("synopsis") val synopsis: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("status") val status: String,
        @JsonProperty("thumbnail") val thumbnail: String
    )

    data class AnimeTypes(
        @JsonProperty("TV") val TV: String,
        @JsonProperty("OVA") val OVA: String,
        @JsonProperty("Movie") val Movie: String,
        @JsonProperty("Special") val Special: String,
        @JsonProperty("ONA") val ONA: String,
        @JsonProperty("Music") val Music: String
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val main = app.get("$mainUrl/ajax/ajax_search/?q=$query").text
        val json = parseJson<MainSearch>(main)
        return json.animes.map {
            val title = it.title
            val href = "$mainUrl/${it.slug}"
            val image = "https://cdn.jkanime.net/assets/images/animes/image/${it.slug}.jpg"
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

    data class EpsInfo (
        @JsonProperty("number" ) var number : String? = null,
        @JsonProperty("title"  ) var title  : String? = null,
        @JsonProperty("image"  ) var image  : String? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document
        val poster = doc.selectFirst(".set-bg")?.attr("data-setbg")
        val title = doc.selectFirst(".anime__details__title > h3")?.text()
        val description = doc.selectFirst(".anime__details__text > p")?.text()
        val genres = doc.select("div.col-lg-6:nth-child(1) > ul:nth-child(1) > li:nth-child(2) > a")
            .map { it.text() }
        val status = when (doc.selectFirst("span.enemision")?.text()) {
            "En emisión" -> ShowStatus.Ongoing
            "En emision" -> ShowStatus.Ongoing
            "Concluido" -> ShowStatus.Completed
            else -> null
        }
        val type = doc.selectFirst("div.col-lg-6.col-md-6 ul li[rel=tipo]")?.text()
        val animeID = doc.selectFirst("div.ml-2")?.attr("data-anime")?.toInt()
        val episodes = ArrayList<Episode>()
        val pags = doc.select("a.numbers").map { it.attr("href").substringAfter("#pag") }.toList()
        pags.apmap { pagnum ->
            val res = app.get("$mainUrl/ajax/pagination_episodes/$animeID/$pagnum/").text
            val json = parseJson<ArrayList<EpsInfo>>(res)
            json.apmap { info ->
                val imagetest = !info.image.isNullOrBlank()
                val image = if (imagetest) "https://cdn.jkdesu.com/assets/images/animes/video/image_thumb/${info.image}" else null
                val link = "${url.removeSuffix("/")}/${info.number}"
                val ep = Episode(
                    link,
                    posterUrl = image
                )
                episodes.add(ep)
            }
        }

        return newAnimeLoadResponse(title!!, url, getType(type!!)) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
        }
    }

    data class Nozomi(
        @JsonProperty("file") val file: String?
    )

    private fun streamClean(
        name: String,
        url: String,
        referer: String,
        quality: String?,
        callback: (ExtractorLink) -> Unit,
        m3u8: Boolean
    ): Boolean {
        callback(
            ExtractorLink(
                name,
                name,
                url,
                referer,
                getQualityFromName(quality),
                m3u8
            )
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.col-lg-12.rounded.bg-servers.text-white.p-3.mt-2 a").forEach { it ->
            val serverId = it.attr("data-id")
            val scriptServers = document.selectFirst("script:containsData(var video = [];)")!!
            val url = scriptServers.data().substringAfter("video[$serverId] = '<iframe class=\"player_conte\" src=\"")
                .substringBefore("\"")
                .replace("/jkfembed.php?u=", "https://embedsito.com/v/")
                .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                .replace("/jkvmixdrop.php?u=", "https://mixdrop.co/e/")
                .replace("/jk.php?u=", "$mainUrl/")

            if (url.isNotEmpty()) loadExtractor(url, data, subtitleCallback, callback)
            
            if (url.contains("um2.php")) {
                val doc = app.get(url, referer = data).document
                val gsplaykey = doc.select("form input[value]").attr("value")
                app.post(
                    "$mainUrl/gsplay/redirect_post.php",
                    headers = mapOf(
                        "Host" to "jkanime.net",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Referer" to url,
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Origin" to "https://jkanime.net",
                        "DNT" to "1",
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                        "TE" to "trailers",
                        "Pragma" to "no-cache",
                        "Cache-Control" to "no-cache",
                    ),
                    data = mapOf(Pair("data", gsplaykey)),
                    allowRedirects = false
                ).okhttpResponse.headers.values("location").apmap { loc ->
                    val postkey = loc.replace("/gsplay/player.html#", "")
                    val nozomitext = app.post(
                        "$mainUrl/gsplay/api.php",
                        headers = mapOf(
                            "Host" to "jkanime.net",
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Accept-Language" to "en-US,en;q=0.5",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to "https://jkanime.net",
                            "DNT" to "1",
                            "Connection" to "keep-alive",
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "same-origin",
                        ),
                        data = mapOf(Pair("v", postkey)),
                        allowRedirects = false
                    ).text
                    val json = parseJson<Nozomi>(nozomitext)
                    val nozomiurl = listOf(json.file)
                    if (nozomiurl.isEmpty()) null else
                        nozomiurl.forEach { url ->
                            val nozominame = "Nozomi"
                            if (url != null) {
                                streamClean(
                                    nozominame,
                                    url,
                                    "",
                                    null,
                                    callback,
                                    url.contains(".m3u8")
                                )
                            }
                        }
                }
            }
            if (url.contains("um.php")) {
                val desutext = app.get(url, referer = data).text
                val desuRegex = Regex("((https:|http:)//.*\\.m3u8)")
                val file = desuRegex.find(desutext)?.value
                val namedesu = "Desu"
                generateM3u8(
                    namedesu,
                    file!!,
                    mainUrl,
                ).forEach { desurl ->
                    streamClean(
                        namedesu,
                        desurl.url,
                        mainUrl,
                        desurl.quality.toString(),
                        callback,
                        true
                    )
                }
            }
            if (url.contains("jkmedia")) {
                app.get(
                    url,
                    referer = data,
                    allowRedirects = false
                ).okhttpResponse.headers.values("location").apmap { xtremeurl ->
                    val namex = "Xtreme S"
                    streamClean(
                        namex,
                        xtremeurl,
                        "",
                        null,
                        callback,
                        xtremeurl.contains(".m3u8")
                    )
                }
            }
        }
        return true
    }
}