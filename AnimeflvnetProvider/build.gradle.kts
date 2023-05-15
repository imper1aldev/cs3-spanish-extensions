// use an integer for version numbers
version = 1


cloudstream {
    language = "es"
    // All of these properties are optional, you can safely remove them

    description = "El mejor portal de anime online para latinoamérica, encuentra animes clásicos, animes del momento, animes más populares y mucho más, todo en animeflv, tu fuente de anime diaria."
    // authors = listOf("Cloudburst")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www3.animeflv.net&sz=%size%"
}