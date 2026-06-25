package com.verystupidsimplepodcast

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.io.ByteArrayOutputStream

class PodcastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(
            SimpleDownloader(),
            org.schabi.newpipe.extractor.localization.Localization("en", "US"),
            org.schabi.newpipe.extractor.localization.ContentCountry("US")
        )
    }
}

class SimpleDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val url = URL(request.url())
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = request.httpMethod()
        
        request.headers().forEach { (key, values) ->
            values.forEach { value ->
                connection.addRequestProperty(key, value)
            }
        }
        
        if (connection.getRequestProperty("User-Agent") == null) {
            connection.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }
        connection.addRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (request.headers()["Cookie"] == null) {
            connection.addRequestProperty("Cookie", "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; CONSENT=YES+cb.20210328-17-p0.en+FX+438")
        }
        
        if (request.dataToSend() != null) {
            connection.doOutput = true
            connection.outputStream.write(request.dataToSend())
        }

        val responseCode = connection.responseCode
        val responseMessage = connection.responseMessage
        
        val headers = mutableMapOf<String, List<String>>()
        connection.headerFields.forEach { (key, value) ->
            if (key != null) headers[key] = value
        }
        
        val inputStream: InputStream? = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        var responseBody: String? = null
        if (inputStream != null) {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } != -1) {
                result.write(buffer, 0, length)
            }
            responseBody = result.toString("UTF-8")
        }

        return Response(responseCode, responseMessage, headers, responseBody, request.url())
    }
}
