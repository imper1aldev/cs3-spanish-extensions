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
        val home = soup.select("#container section.search-videos div.section-content div.row div div.col-xs-6 div.video-post").map {
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
        val title = soup.selectFirst(".info-content h1")?.text()
        val description = soup.selectFirst("span.sinopsis")?.text()?.trim()
        val poster: String? = soup.selectFirst(".poster img")?.attr("src")
        val episodes = soup.select(".item-season-episodes a").map { li ->
            val href = fixUrl(li.selectFirst("a")?.attr("href") ?: "")
            val name = li.selectFirst("a")?.text() ?: ""
            Episode(
                href, name,
            )
        }.reversed()

        val year = Regex("(\\d*)").find(soup.select(".info-half").text())

        val tvType = if (url.contains("/pelicula/")) TvType.AnimeMovie else TvType.Anime
        val genre = soup.select(".content-type-a a")
            .map { it?.text()?.trim().toString().replace(", ", "") }
        val duration = Regex("""(\d*)""").find(
            soup.select("p.info-half:nth-child(4)").text()
        )

        return when (tvType) {
            TvType.Anime -> {
                return newAnimeLoadResponse(title ?: "", url, tvType) {
                    japName = null
                    engName = title
                    posterUrl = poster
                    this.year = null
                    addEpisodes(DubStatus.Subbed, episodes)
                    plot = description
                    tags = genre

                    showStatus = null
                }
            }
            TvType.AnimeMovie -> {
                MovieLoadResponse(
                    title ?: "",
                    url,
                    this.name,
                    tvType,
                    url,
                    poster,
                    year.toString().toIntOrNull(),
                    description,
                    null,
                    genre,
                    duration.toString().toIntOrNull(),
                )
            }
            else -> null
        }
    }

    data class MainJson(
        @JsonProperty("source") val source: List<Source>,
        @JsonProperty("source_bk") val sourceBk: String?,
        @JsonProperty("track") val track: List<String>?,
        @JsonProperty("advertising") val advertising: List<String>?,
        @JsonProperty("linkiframe") val linkiframe: String?
    )

    data class Source(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("default") val default: String,
        @JsonProperty("type") val type: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("li.tab-video").apmap {
            val url = fixUrl(it.attr("data-video"))
            if (url.contains("animeid")) {
                val ajaxurl = url.replace("streaming.php", "ajax.php")
                val ajaxurltext = app.get(ajaxurl).text
                val json = parseJson<MainJson>(ajaxurltext)
                json.source.forEach { source ->
                    if (source.file.contains("m3u8")) {
                        generateM3u8(
                            "Animeflv.io",
                            source.file,
                            "https://animeid.to",
                            headers = mapOf("Referer" to "https://animeid.to")
                        ).apmap {
                            callback(
                                ExtractorLink(
                                    "Animeflv.io",
                                    "Animeflv.io",
                                    it.url,
                                    "https://animeid.to",
                                    getQualityFromName(it.quality.toString()),
                                    it.url.contains("m3u8")
                                )
                            )
                        }
                    } else {
                        callback(
                            ExtractorLink(
                                name,
                                "$name ${source.label}",
                                source.file,
                                "https://animeid.to",
                                Qualities.Unknown.value,
                                isM3u8 = source.file.contains("m3u8")
                            )
                        )
                    }
                }
            }
            loadExtractor(url, data, subtitleCallback, callback)
        }
        return true
    }
}