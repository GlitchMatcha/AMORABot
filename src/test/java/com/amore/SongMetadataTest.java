package com.amore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SongMetadataTest {

    @Test
    void youTubeUploadsSplitArtistAndDropVideoNoise() {
        assertEquals(new SongMetadata.DisplayMeta("Blinding Lights", "The Weeknd"),
                SongMetadata.fromYouTube("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"));
        assertEquals(new SongMetadata.DisplayMeta("Shut Down", "BLACKPINK"),
                SongMetadata.fromYouTube("BLACKPINK - ‘Shut Down’ M/V", "BLACKPINK"));
        assertEquals(new SongMetadata.DisplayMeta("Idol", "YOASOBI"),
                SongMetadata.fromYouTube("YOASOBI「Idol」Official Music Video", "Ayase / YOASOBI"));
    }

    @Test
    void topicChannelsKeepTheTitleAndCleanTheChannelName() {
        assertEquals(new SongMetadata.DisplayMeta("Cruel Summer", "Taylor Swift"),
                SongMetadata.fromYouTube("Cruel Summer", "Taylor Swift - Topic"));
    }

    @Test
    void featCreditsAndRemixTagsSurviveCleaning() {
        assertEquals("Peaches (feat. Daniel Caesar)", SongMetadata.cleanTitle("Peaches (feat. Daniel Caesar) [Official Video]"));
        assertEquals("Song (Remix)", SongMetadata.cleanTitle("Song (Remix)"));
    }

    @Test
    void guessesIgnoreCaseAccentsPunctuationFeatAndSmallTypos() {
        assertTrue(SongMetadata.isCorrectGuess("despacito", "Despacito (feat. Daddy Yankee)"));
        assertTrue(SongMetadata.isCorrectGuess("pokemon theme", "Pokémon Theme!"));
        assertTrue(SongMetadata.isCorrectGuess("blinding lihgts", "Blinding Lights"));
        assertTrue(SongMetadata.isCorrectGuess("Less Than Zero", "The Less Than Zero"));
        assertTrue(SongMetadata.isCorrectGuess("Rock and Roll", "Rock & Roll"));
    }

    @Test
    void guessesRejectDifferentOrPartialTitles() {
        assertFalse(SongMetadata.isCorrectGuess("lights", "Blinding Lights"));
        assertFalse(SongMetadata.isCorrectGuess("ok", "OK Computer"));
        assertFalse(SongMetadata.isCorrectGuess("Hello", "Halo"));
        assertFalse(SongMetadata.isCorrectGuess("", "Halo"));
    }

    @Test
    void sameSongFromDifferentSourcesSharesADedupeKey() {
        SongMetadata.DisplayMeta yt = SongMetadata.fromYouTube("The Weeknd - Blinding Lights (Official Audio)", "TheWeekndVEVO");
        SongMetadata.DisplayMeta member = SongMetadata.fromMember("blinding lights", "the weeknd");
        assertEquals(SongMetadata.dedupeKey(member.title(), member.artist()), SongMetadata.dedupeKey(yt.title(), yt.artist()));
    }

    @Test
    void tagsAreValidatedAgainstTheVocabularyAndCapped() {
        assertEquals(",kpop,hype,", SongMetadata.parseTags("K-Pop, #hype"));
        assertEquals("", SongMetadata.parseTags("  "));
        assertNull(SongMetadata.parseTags("polka"));
        assertNull(SongMetadata.parseTags("chill hype sad love"));
    }

    @Test
    void linksNormalizeAcrossShareFormats() {
        assertEquals("https://www.youtube.com/watch?v=abc123", SongMetadata.normalizeLink("https://youtu.be/abc123?si=x"));
        assertEquals("https://www.youtube.com/watch?v=abc123", SongMetadata.normalizeLink("https://music.youtube.com/watch?v=abc123&list=RD"));
        assertEquals("https://open.spotify.com/track/7ouMYWpwJ422jRcDASZB7P",
                SongMetadata.normalizeLink("https://open.spotify.com/intl-de/track/7ouMYWpwJ422jRcDASZB7P?si=1"));
        assertFalse(SongMetadata.isSupportedLink("https://open.spotify.com/album/123"));
    }
}
