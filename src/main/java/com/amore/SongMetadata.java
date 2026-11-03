package com.amore;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helpers for song links, display metadata, tags and answer matching. */
public final class SongMetadata {

    private SongMetadata() {
    }

    /** Tags members may attach to songs; also drives themed days. */
    public static final List<String> TAG_VOCABULARY = List.of(
            "chill", "hype", "sad", "love", "throwback", "acoustic", "pop", "kpop", "jpop",
            "rnb", "rap", "rock", "indie", "edm", "anime", "underrated");

    private static final Pattern NOISE_BRACKET = Pattern.compile(
            "\\s*[\\(\\[【「]([^\\)\\]】」]*?)(official|video|audio|lyric|lyrics|mv|m/v|visuali[sz]er|\\bhd\\b|4k|remaster|live|"
                    + "color coded|performance|teaser|clip|dance practice|prod\\.|explicit|clean)([^\\)\\]】」]*)[\\)\\]】」]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_NOISE = Pattern.compile(
            "\\s+(official (music )?video|official audio|official mv|m/v|mv|lyric video|lyrics|visuali[sz]er)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("[‘'\"“「『]([^’'\"”」』]{1,120})[’'\"”」』]");
    private static final Pattern DASH_SPLIT = Pattern.compile("\\s+[-–—]\\s+");
    private static final Pattern FEAT = Pattern.compile(
            "[\\(\\[]?\\s*\\b(feat\\.?|ft\\.?|featuring|with)\\s+[^\\)\\]]*[\\)\\]]?", Pattern.CASE_INSENSITIVE);

    public record DisplayMeta(String title, String artist) {
    }

    // ---------------------------------------------------------------- links

    public static String normalizeLink(String raw) {
        if (raw == null) return "";
        String link = raw.trim();
        if (link.isBlank()) return "";
        try {
            URI uri = URI.create(link);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);

            if (host.equals("youtu.be")) {
                String videoId = uri.getPath() == null ? "" : uri.getPath().replace("/", "").trim();
                if (!videoId.isBlank()) return "https://www.youtube.com/watch?v=" + videoId;
            }
            if (host.equals("youtube.com") || host.equals("m.youtube.com") || host.equals("music.youtube.com")) {
                String videoId = queryParam(uri.getRawQuery(), "v");
                if (videoId != null && !videoId.isBlank()) return "https://www.youtube.com/watch?v=" + videoId;
            }
            if (host.equals("open.spotify.com")) {
                String path = uri.getPath() == null ? "" : uri.getPath().trim();
                int trackAt = path.indexOf("/track/");
                if (trackAt != -1) { // also handles /intl-xx/track/<id>
                    String trackId = path.substring(trackAt + "/track/".length());
                    int slash = trackId.indexOf('/');
                    if (slash != -1) trackId = trackId.substring(0, slash);
                    if (!trackId.isBlank()) return "https://open.spotify.com/track/" + trackId;
                }
            }
        } catch (Exception ignored) {
        }
        return link;
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            if (pair.substring(0, idx).equals(key)) {
                return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    public static boolean isSupportedLink(String link) {
        String n = normalizeLink(link).toLowerCase(Locale.ROOT);
        return n.startsWith("https://open.spotify.com/track/") || n.startsWith("https://www.youtube.com/watch?v=");
    }

    public static boolean isYouTubePlaylistLink(String link) {
        if (link == null || link.isBlank()) return false;
        String lower = link.trim().toLowerCase(Locale.ROOT);
        if ((lower.contains("youtube.com") || lower.contains("youtu.be")) && lower.contains("list=")) return true;
        return link.startsWith("PL") || link.startsWith("OL") || link.startsWith("RD") || link.startsWith("UU");
    }

    public static String detectSource(String link) {
        return normalizeLink(link).toLowerCase(Locale.ROOT).contains("spotify") ? "Spotify" : "YouTube";
    }

    /** YouTube video id of a normalized watch link, or null. */
    public static String youTubeVideoId(String link) {
        String n = normalizeLink(link);
        String prefix = "https://www.youtube.com/watch?v=";
        return n.startsWith(prefix) ? n.substring(prefix.length()) : null;
    }

    // ---------------------------------------------------------------- display metadata

    /** Strips video-noise like "(Official Music Video)" from a title. */
    public static String cleanTitle(String title) {
        if (title == null) return "";
        String t = title.replaceAll("\\s+", " ").trim();
        String previous;
        do {
            previous = t;
            t = NOISE_BRACKET.matcher(t).replaceAll("").trim();
        } while (!t.equals(previous));
        int pipe = t.indexOf(" | ");
        if (pipe > 0) t = t.substring(0, pipe).trim();
        t = TRAILING_NOISE.matcher(t).replaceAll("").trim();
        return t.isBlank() ? title.trim() : t;
    }

    /** Strips channel suffixes like " - Topic" or "VEVO" from an artist/channel name. */
    public static String cleanArtist(String artist) {
        if (artist == null) return "";
        String a = artist.replaceAll("\\s+", " ").trim();
        a = a.replaceAll("(?i)\\s*-\\s*topic$", "");
        a = a.replaceAll("VEVO$", "");
        a = a.replaceAll("(?i)\\s+(official( channel| youtube channel)?)$", "");
        a = a.trim();
        return a.isBlank() ? artist.trim() : a;
    }

    /**
     * Display metadata for a YouTube playlist item, where the "artist" is the uploading channel.
     * Handles "Artist - Title", K-pop style "Artist 'Title' M/V" and topic-channel uploads.
     */
    public static DisplayMeta fromYouTube(String rawTitle, String channel) {
        String title = rawTitle == null ? "" : rawTitle.replaceAll("\\s+", " ").trim();
        String artist = cleanArtist(channel);

        String[] dash = DASH_SPLIT.split(title, 2);
        if (dash.length == 2 && !dash[0].isBlank() && !dash[1].isBlank()) {
            String right = dash[1];
            Matcher quoted = QUOTED.matcher(right);
            String songTitle = quoted.find() ? quoted.group(1) : right;
            return new DisplayMeta(cleanTitle(songTitle), cleanArtist(dash[0]));
        }

        Matcher quoted = QUOTED.matcher(title);
        if (quoted.find() && quoted.start() > 0) {
            String before = title.substring(0, quoted.start()).trim();
            return new DisplayMeta(cleanTitle(quoted.group(1)), before.isBlank() ? artist : cleanArtist(before));
        }

        return new DisplayMeta(cleanTitle(title), artist);
    }

    /** Display metadata for a member submission: keep their wording, drop video noise. */
    public static DisplayMeta fromMember(String title, String artist) {
        return new DisplayMeta(cleanTitle(title), artist == null ? "" : artist.replaceAll("\\s+", " ").trim());
    }

    // ---------------------------------------------------------------- matching

    /** Lowercase, accent-free, punctuation-free key with "feat." credits removed. */
    public static String matchKey(String value) {
        if (value == null) return "";
        String v = FEAT.matcher(value).replaceAll(" ");
        v = Normalizer.normalize(v, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        v = v.toLowerCase(Locale.ROOT).replace("&", " and ");
        v = v.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
        return v;
    }

    /** Cross-source duplicate key: the same song from Spotify and YouTube collapses to one key. */
    public static String dedupeKey(String displayTitle, String displayArtist) {
        String t = matchKey(displayTitle);
        if (t.isEmpty()) return "";
        return matchKey(displayArtist) + "|" + t;
    }

    /** Lenient answer check for guessing games: ignores case, accents, punctuation, "feat." and small typos. */
    public static boolean isCorrectGuess(String guess, String answer) {
        String g = matchKey(guess);
        String a = matchKey(answer);
        if (g.isEmpty() || a.isEmpty()) return false;
        if (g.equals(a)) return true;

        String aNoParen = matchKey(answer.replaceAll("[\\(\\[][^\\)\\]]*[\\)\\]]", " "));
        if (!aNoParen.isEmpty() && g.equals(aNoParen)) return true;
        if (stripArticle(g).equals(stripArticle(aNoParen.isEmpty() ? a : aNoParen))) return true;

        String target = aNoParen.isEmpty() ? a : aNoParen;
        if (target.length() < 4) return false;
        int allowed = Math.max(1, target.length() / 6);
        return levenshtein(g, target, allowed) <= allowed;
    }

    private static String stripArticle(String s) {
        return s.startsWith("the ") ? s.substring(4) : s;
    }

    /** Edit distance with early exit once every cell in a row exceeds {@code limit}. */
    static int levenshtein(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) return limit + 1;
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    // ---------------------------------------------------------------- tags

    /**
     * Parses comma/space separated tags against {@link #TAG_VOCABULARY}.
     * Returns the stored form ",a,b," (empty string for none) or null when a tag is unknown or more than 3 are given.
     */
    public static String parseTags(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Set<String> tags = new LinkedHashSet<>();
        for (String part : raw.toLowerCase(Locale.ROOT).split("[,\\s#]+")) {
            String tag = part.trim().replace("-", "");
            if (tag.isEmpty()) continue;
            if (!TAG_VOCABULARY.contains(tag)) return null;
            tags.add(tag);
        }
        if (tags.size() > 3) return null;
        return tags.isEmpty() ? "" : "," + String.join(",", tags) + ",";
    }

    public static List<String> tagList(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null) return out;
        for (String t : stored.split(",")) {
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- text

    public static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** "Shut Down" -> "▢▢▢▢ ▢▢▢▢": letters/digits hidden, separators kept. */
    public static String titleShape(String title) {
        StringBuilder sb = new StringBuilder();
        for (char c : cleanTitle(title).toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? '▢' : c);
        }
        return sb.toString();
    }

    /** "Taylor Swift" -> "T. S." */
    public static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (!word.isEmpty()) sb.appendCodePoint(Character.toUpperCase(word.codePointAt(0))).append(". ");
        }
        return sb.toString().trim();
    }
}
