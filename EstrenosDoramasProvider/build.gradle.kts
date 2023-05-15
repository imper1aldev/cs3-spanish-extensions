// use an integer for version numbers
version = 1


cloudstream {
    language = "es"
    // All of these properties are optional, you can safely remove them

    description = "mira antes que nadie los ultimos doramas online en emision y finalizados, aqui podras ver muchisimos doramas online y novelas coreanas"
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
        "AsianDrama",
        "Movie",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www23.estrenosdoramas.net&sz=%size%"
}