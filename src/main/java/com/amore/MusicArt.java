package com.amore;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.json.JSONObject;

/** Song artwork lookup (oEmbed) and image rendering for music games and recaps. */
public final class MusicArt {

    private MusicArt() {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final int CACHE_LIMIT = 2000;
    /** link -> artwork URL ("" = known to have none). */
    private static final Map<String, String> ARTWORK_URLS = new ConcurrentHashMap<>();

    /** Artwork URL for a Spotify/YouTube link, or null. Blocking: never call before acknowledging an interaction. */
    public static String artworkUrl(String link) {
        if (link == null || link.isBlank()) return null;
        String cached = ARTWORK_URLS.get(link);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String lower = link.toLowerCase();
        String endpoint = null;
        if (lower.contains("spotify.com/")) {
            endpoint = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(link, StandardCharsets.UTF_8);
        } else if (lower.contains("youtube.com/") || lower.contains("youtu.be/")) {
            endpoint = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(link, StandardCharsets.UTF_8);
        }
        String url = null;
        if (endpoint != null) {
            try {
                HttpResponse<String> res = HTTP.send(get(endpoint), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    url = new JSONObject(res.body()).optString("thumbnail_url", null);
                }
            } catch (Exception e) {
                System.err.println("Artwork lookup failed for " + link + ": " + e.getMessage());
                return null; // transient: do not cache
            }
        }
        if (ARTWORK_URLS.size() > CACHE_LIMIT) ARTWORK_URLS.clear();
        ARTWORK_URLS.put(link, url == null ? "" : url);
        return url;
    }

    /** Downloads and decodes the song's artwork, or null. */
    public static BufferedImage artwork(String link) {
        String url = artworkUrl(link);
        if (url == null) return null;
        try {
            HttpResponse<byte[]> res = HTTP.send(get(url), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) return null;
            return ImageIO.read(new ByteArrayInputStream(res.body()));
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "Mozilla/5.0 (AMORA Bot)")
                .GET().build();
    }

    /** Center-crops to a square and renders it at {@code size}px with blocks of {@code blockSize}px (1 = sharp). */
    public static byte[] pixelatedPng(BufferedImage source, int size, int blockSize) {
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage square = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);

        int cells = Math.max(1, size / Math.max(1, blockSize));
        BufferedImage small = new BufferedImage(cells, cells, BufferedImage.TYPE_INT_RGB);
        Graphics2D gs = small.createGraphics();
        gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gs.drawImage(square, 0, 0, cells, cells, null);
        gs.dispose();

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, blockSize <= 1
                ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(blockSize <= 1 ? square : small, 0, 0, size, size, null);
        g.dispose();
        return png(out);
    }

    public record RecapLine(String title, String subtitle) {
    }

    /** Renders the monthly recap card. */
    public static byte[] recapCard(String heading, String period, List<RecapLine> topSongs, List<RecapLine> highlights,
                                   BufferedImage heroArtwork) {
        int w = 1000, h = 560;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        g.setPaint(new GradientPaint(0, 0, new Color(43, 16, 64), w, h, new Color(140, 30, 90)));
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, 40, 40));

        g.setColor(new Color(255, 255, 255, 30));
        for (int i = 0; i < 6; i++) {
            g.setStroke(new BasicStroke(2f));
            g.drawOval(640 + i * 14, -160 + i * 14, 520 - i * 28, 520 - i * 28);
        }

        g.setColor(new Color(255, 182, 213));
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString(period.toUpperCase(), 48, 62);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        g.drawString(heading, 48, 108);

        int artX = 680, artY = 140, artSize = 272;
        if (heroArtwork != null) {
            byte[] sharp = pixelatedPng(heroArtwork, artSize, 1);
            try {
                BufferedImage art = ImageIO.read(new ByteArrayInputStream(sharp));
                g.setClip(new RoundRectangle2D.Double(artX, artY, artSize, artSize, 28, 28));
                g.drawImage(art, artX, artY, null);
                g.setClip(null);
            } catch (Exception ignored) {
            }
        } else {
            g.setColor(new Color(255, 255, 255, 40));
            g.fill(new RoundRectangle2D.Double(artX, artY, artSize, artSize, 28, 28));
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 96));
            g.drawString("♪", artX + 100, artY + 170);
        }

        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.setColor(new Color(255, 182, 213));
        g.drawString("TOP SONGS", 48, 160);
        int y = 196;
        for (int i = 0; i < topSongs.size() && i < 5; i++) {
            RecapLine line = topSongs.get(i);
            g.setColor(i == 0 ? new Color(255, 215, 0) : Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 24));
            g.drawString("#" + (i + 1) + "  " + SongMetadata.truncate(line.title(), 34), 48, y);
            g.setColor(new Color(230, 210, 230));
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g.drawString(SongMetadata.truncate(line.subtitle(), 64), 92, y + 22);
            y += 56;
        }
        if (topSongs.isEmpty()) {
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.ITALIC, 20));
            g.drawString("No rated songs this month yet.", 48, y);
        }

        int hx = 48, hy = 488;
        for (RecapLine line : highlights) {
            g.setColor(new Color(255, 255, 255, 36));
            g.fill(new RoundRectangle2D.Double(hx, hy - 34, 196, 62, 18, 18));
            g.setColor(new Color(255, 182, 213));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString(line.title().toUpperCase(), hx + 14, hy - 12);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 17));
            g.drawString(SongMetadata.truncate(line.subtitle(), 20), hx + 14, hy + 12);
            hx += 210;
        }

        g.setColor(new Color(255, 255, 255, 150));
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("AMORA ✦ Music Recap", artX, h - 28);
        g.dispose();
        return png(img);
    }

    private static byte[] png(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PNG encoding failed", e);
        }
    }
}
