// use an integer for version numbers
version = 1


cloudstream {
    language = "es"
    // All of these properties are optional, you can safely remove them

    description = "Dramas coreanos, chinos, Tailandia, Japones, mira antes que nadie los últimos doramas online en emisión y finalizados en HD."
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
        "Anime",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=doramasyt.com&sz=%size%"
}