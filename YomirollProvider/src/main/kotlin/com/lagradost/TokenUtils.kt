package com.lagradost

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.requestCreator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.*

class TokenUtils {
    private val crApiLink = "https://beta-api.crunchyroll.com"
    private val crBasicToken = "b2VkYXJteHN0bGgxanZhd2ltbnE6OWxFaHZIWkpEMzJqdVY1ZFc5Vk9TNTdkb3BkSnBnbzE="
    fun getCrunchyrollToken(): Map<String, String> {
        val client = app.baseClient.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("cr-unblocker.us.to", 1080)))
            .build()

        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication("crunblocker", "crunblocker".toCharArray())
            }
        })

        val refreshToken = client.newCall(requestCreator(
            url = "https://raw.githubusercontent.com/Samfun75/File-host/main/aniyomi/refreshToken.txt",
            method = "GET"
        )).execute().body.string().replace("[\n\r]".toRegex(), "")

        val request = requestCreator(
            method = "POST",
            url = "$crApiLink/auth/v1/token",
            headers = mapOf(
                "User-Agent" to "Crunchyroll/3.26.1 Android/11 okhttp/4.9.2",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Authorization" to "Basic $crBasicToken"
            ),
            data = mapOf(
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
                "scope" to "offline_access"
            )
        )

        val response = tryParseJson<CrunchyrollToken>(client.newCall(request).execute().body.string())
        return mapOf("Authorization" to "${response?.tokenType} ${response?.accessToken}")
    }
}