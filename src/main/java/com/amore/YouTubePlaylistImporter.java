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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            throw new IllegalStateException("YOUTUBE_API_KEY is missing from environment variables.");
        }

        String playlistId = extractPlaylistId(playlistUrl);
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Invalid YouTube playlist URL or ID. Make sure the URL contains 'list=' or input the ID directly.");
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

            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                String message = (error.has("message") && !error.get("message").isJsonNull()) 
                        ? error.get("message").getAsString() 
                        : "Unknown API error.";
                throw new IllegalStateException("YouTube API error: " + message);
            }

            if (root.has("items") && root.get("items").isJsonArray()) {
                JsonArray items = root.getAsJsonArray("items");
                for (JsonElement element : items) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();

                    if (!item.has("snippet") || !item.get("snippet").isJsonObject()) continue;
                    JsonObject snippet = item.getAsJsonObject("snippet");

                    String title = safeGet(snippet, "title");
                    if ("Deleted video".equalsIgnoreCase(title) || "Private video".equalsIgnoreCase(title)) {
                        continue;
                    }

                    if (!snippet.has("resourceId") || !snippet.get("resourceId").isJsonObject()) continue;
                    JsonObject resourceId = snippet.getAsJsonObject("resourceId");

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

            pageToken = (root.has("nextPageToken") && root.get("nextPageToken").isJsonPrimitive()) 
                    ? root.get("nextPageToken").getAsString() 
                    : null;

        } while (pageToken != null && !pageToken.isBlank());

        return songs;
    }

    public static boolean isConfigured() {
        return API_KEY != null && !API_KEY.isBlank();
    }

    /** View counts for up to 50 video ids (YouTube Data API videos.list, 1 quota unit). Missing/private videos are absent. */
    public static Map<String, Long> fetchViewCounts(List<String> videoIds) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("YOUTUBE_API_KEY is missing from environment variables.");
        }
        Map<String, Long> out = new HashMap<>();
        if (videoIds.isEmpty()) return out;
        String url = "https://www.googleapis.com/youtube/v3/videos?part=statistics&maxResults=50&id="
                + encode(String.join(",", videoIds.subList(0, Math.min(50, videoIds.size()))))
                + "&key=" + encode(API_KEY);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("YouTube API returned HTTP " + response.statusCode() + ".");
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (root.has("items") && root.get("items").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("items")) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String id = safeGet(item, "id");
                if (id.isBlank() || !item.has("statistics") || !item.get("statistics").isJsonObject()) continue;
                String views = safeGet(item.getAsJsonObject("statistics"), "viewCount");
                if (views.isBlank()) continue;
                try {
                    out.put(id, Long.parseLong(views));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
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
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
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

        String trimmed = raw.trim();
        
        if (!trimmed.contains("youtube.com") && !trimmed.contains("youtu.be")) {
            if (trimmed.startsWith("PL") || trimmed.startsWith("OL") || trimmed.startsWith("RD") || trimmed.startsWith("UU")) {
                return trimmed;
            }
            return null; 
        }

        try {
            URI uri = URI.create(trimmed.replace(" ", "%20"));
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