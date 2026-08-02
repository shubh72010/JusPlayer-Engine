package org.jusplayer.engine.provider.newpipe.mapping

import org.jusplayer.engine.model.Song
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongMapperTest {

    @Test
    fun mapsStreamInfoItemToSong() {
        val item = StreamInfoItem(0, "https://youtube.com/watch?v=abc", "Song Title", StreamType.AUDIO_STREAM)
        item.setDuration(180)
        item.setUploaderName("Artist Name")
        item.setUploaderUrl("https://youtube.com/channel/xyz")
        item.setThumbnails(listOf(Image("https://img.example.com/thumb.jpg", 720, 1280, Image.ResolutionLevel.HIGH)))

        val song = SongMapper.map(item)

        assertEquals("https://youtube.com/watch?v=abc", song.id)
        assertEquals("Song Title", song.title)
        assertEquals("Artist Name", song.artists.first().name)
        assertEquals(180, song.duration)
        assertEquals("https://img.example.com/thumb.jpg", song.thumbnailUrl)
    }

    @Test
    fun mapsSongWithoutUploaderToEmptyArtists() {
        val item = StreamInfoItem(0, "https://youtube.com/watch?v=abc", "No Artist", StreamType.VIDEO_STREAM)
        item.setDuration(60)

        val song: Song = SongMapper.map(item)

        assertTrue(song.artists.isEmpty())
    }
}
