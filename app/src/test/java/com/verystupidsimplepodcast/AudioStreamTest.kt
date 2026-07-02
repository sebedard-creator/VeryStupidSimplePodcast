package com.verystupidsimplepodcast

import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.NewPipe

class AudioStreamTest {
    @Test
    fun testAudioStreams() {
        // We might not need NewPipe.init for simple extractor if Downloader is already initialized by the app?
        // Actually, NewPipe must be initialized.
        try {
            val url = "https://www.youtube.com/watch?v=0e3GPea1Tyg"
            val extractor = ServiceList.YouTube.getStreamExtractor(url)
            extractor.fetchPage()
            
            val audioStreams = extractor.audioStreams
            println("Total audio streams: ${audioStreams.size}")
            for (stream in audioStreams) {
                // We'll print basic fields that are public
                println("Stream Bitrate: ${stream.averageBitrate} | Format: ${stream.format.name} | Content: ${stream.content.take(50)}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
