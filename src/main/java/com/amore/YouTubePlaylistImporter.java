package com.amore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class YouTubePlaylistImporter {

    private static final String API_KEY = System.getenv("YOUTUBE_API_KEY");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private YouTubePlaylistImporter() {
    }

    public record ImportedSong(String title, String artist, String link) {
    }

    public static List<ImportedSong> importPlaylist(String playlistUrl) throws Exception {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IllegalStateException("YOUTUBE_API_KEY is missing.");
        }

        String playlistId = extractPlaylistId(playlistUrl);
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Invalid YouTube playlist URL.");
        }

        List<ImportedSong> songs = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder("https://www.googleapis.com/youtube/v3/playlistItems")
                    .append("?part=snippet")
                    .append("&maxResults=50")
                    .append("&playlistId=").append(encode(playlistId))
                    .append("&key=").append(encode(API_KEY));

            if (pageToken != null && !pageToken.isBlank()) {
                url.append("&pageToken=").append(encode(pageToken));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("YouTube API returned HTTP " + response.statusCode() + ".");
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();

            if (root.has("error")) {
                JsonObject error = root.getAsJsonObject("error");
                String message = error.has("message") ? error.get("message").getAsString() : "Unknown API error.";
                throw new IllegalStateException("YouTube API error: " + message);
            }

            JsonArray items = root.getAsJsonArray("items");
            if (items != null) {
                for (JsonElement element : items) {
                    JsonObject item = element.getAsJsonObject();
                    JsonObject snippet = item.getAsJsonObject("snippet");
                    if (snippet == null) {
                        continue;
                    }

                    String title = safeGet(snippet, "title");
                    if ("Deleted video".equalsIgnoreCase(title) || "Private video".equalsIgnoreCase(title)) {
                        continue;
                    }

                    JsonObject resourceId = snippet.getAsJsonObject("resourceId");
                    if (resourceId == null || !resourceId.has("videoId")) {
                        continue;
                    }

                    String videoId = safeGet(resourceId, "videoId");
                    if (videoId.isBlank()) {
                        continue;
                    }

                    String artist = resolveArtist(snippet);
                    if (artist.isBlank()) {
                        artist = "Unknown Artist";
                    }

                    songs.add(new ImportedSong(
                            clean(title),
                            clean(artist),
                            "https://www.youtube.com/watch?v=" + videoId
                    ));
                }
            }

            pageToken = root.has("nextPageToken") ? root.get("nextPageToken").getAsString() : null;

        } while (pageToken != null && !pageToken.isBlank());

        return songs;
    }

    private static String resolveArtist(JsonObject snippet) {
        String owner = safeGet(snippet, "videoOwnerChannelTitle");
        if (!owner.isBlank()) {
            return owner;
        }

        String channel = safeGet(snippet, "channelTitle");
        if (!channel.isBlank()) {
            return channel;
        }

        return "";
    }

    private static String safeGet(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String extractPlaylistId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(raw.trim());
            String query = uri.getRawQuery();

            if (query != null) {
                for (String pair : query.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx <= 0) {
                        continue;
                    }

                    String key = pair.substring(0, idx);
                    String value = pair.substring(idx + 1);

                    if (key.equals("list")) {
                        return URLDecoder.decode(value, StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}