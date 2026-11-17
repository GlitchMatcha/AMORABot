package com.amore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;

/**
 * Switches and lists staff edit on the web dashboard (AM0RA › Settings), stored in bot_state and cached for a
 * minute so chat handlers can check them on every message without a database call.
 *
 * Switches are on unless their bot_state value is "0". A list falls back to the built-in one when it is missing,
 * empty or not a JSON array of strings.
 */
final class BotSettings {

    static final String LOUNGE_QUOTES_ON = "lounge_quotes_enabled";
    static final String LOUNGE_QUOTES = "lounge_quotes";
    static final String STATUS_MESSAGES = "status_messages";
    static final String GAME_SPOTLIGHT_ON = "game_spotlight_enabled";
    static final String CHAT_SPARKS_ON = "chat_sparks_enabled";
    static final String CHAT_QUESTIONS_ON = "chat_questions_enabled";
    static final String CHAT_REMINDERS_ON = "chat_reminders_enabled";
    static final String CHAT_GAMES_ON = "chat_games_enabled";

    private static final long TTL_MS = 60_000L;
    private static final int MAX_ITEM_LENGTH = 1000;

    private record Cached(String value, long at) { }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private BotSettings() { }

    private static String value(String key) {
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(key);
        if (cached != null && now - cached.at() < TTL_MS) return cached.value();
        String value;
        try {
            value = DatabaseManager.getInstance().getBotState(key);
        } catch (Exception e) {
            value = cached == null ? null : cached.value();  // keep the last known value while the database is away
        }
        CACHE.put(key, new Cached(value, now));
        return value;
    }

    static boolean enabled(String key) {
        return !"0".equals(value(key));
    }

    static List<String> list(String key, List<String> fallback) {
        String raw = value(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            JSONArray array = new JSONArray(raw);
            List<String> items = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String item = array.optString(i, "").trim();
                if (!item.isEmpty() && item.length() <= MAX_ITEM_LENGTH) items.add(item);
            }
            return items.isEmpty() ? fallback : items;
        } catch (Exception e) {
            return fallback;
        }
    }
}
