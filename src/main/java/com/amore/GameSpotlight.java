package com.amore;

import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

/**
 * Promotes BubbleGum Dance Studio, a Roblox game (BETA) our friend Owl works on: a self-replacing lounge drop
 * (like the quote engine), the {@code /game} command, and a bot status line.
 */
public final class GameSpotlight extends ListenerAdapter {

    private static final String GAME_NAME = "BubbleGum Dance Studio";
    private static final String OWL_ID = "162989003405131776";
    private static final long PLACE_ID = 114458132911995L;
    private static final String ROBLOX_URL = "https://www.roblox.com/games/114458132911995/BubbleGum-Dance-Studio";
    private static final String DISCORD_URL = "https://discord.gg/rjUVQ8WPD7";
    private static final String ICON_API = "https://thumbnails.roblox.com/v1/places/gameicons?placeIds=" + PLACE_ID
            + "&size=512x512&format=Png&isCircular=false";

    private static final String LAST_DROP_KEY = "last_game_spotlight_msg_id";
    /** Member messages required since the previous drop; in a quiet chat the old drop is still on screen. */
    private static final int MIN_MESSAGES_BETWEEN_DROPS = 15;
    private static final long DROP_EVERY_HOURS = 6;
    /** Roblox icon URLs are signed and expire, so the URL is re-resolved periodically instead of hardcoded. */
    private static final Duration ICON_TTL = Duration.ofHours(12);

    private static final List<String> HOOKS = List.of(
            "Bring your best moves, the dance floor is waiting! 💃",
            "Grab your friends and hit the studio together! 🫧",
            "Early access vibes: play it before everyone else does! ✨",
            "Bubblegum-pink beats and good vibes only. 🎀",
            "Your next favorite Roblox hangout might be in BETA right now. 🌸",
            "Help a friend's game grow: every visit makes a difference! 💖",
            "Found a bug? The devs would love to hear about it in their Discord! 🐛",
            "Stretch, warm up, and show off your moves! 🩰"
    );

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    private static volatile String iconUrl;
    private static volatile Instant iconFetchedAt = Instant.EPOCH;

    public static CommandData command() {
        return Commands.slash("game", "Check out BubbleGum Dance Studio, a Roblox game (BETA) by our friend Owl!");
    }

    /**
     * Quote drops fire every 2h starting at boot (even hours); starting at +1h and repeating every 6h keeps
     * spotlights on odd hours so the two never land together.
     */
    public static void start(JDA jda) {
        SCHEDULER.execute(GameSpotlight::refreshIconIfStale);
        String loungeId = System.getenv("LOUNGE_CHANNEL_ID");
        if (loungeId == null || loungeId.isBlank()) {
            System.out.println("⚠️ LOUNGE_CHANNEL_ID is not set. Game Spotlight drops are disabled.");
            return;
        }
        SCHEDULER.scheduleAtFixedRate(() -> drop(jda, loungeId), 1, DROP_EVERY_HOURS, TimeUnit.HOURS);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("game")) return;
        event.replyEmbeds(embed()).setComponents(buttons()).queue();
        SCHEDULER.execute(GameSpotlight::refreshIconIfStale);
    }

    /** Replaces the previous drop, but only once the chat has moved on since it was posted. */
    private static void drop(JDA jda, String loungeId) {
        try {
            TextChannel lounge = jda.getTextChannelById(loungeId);
            if (lounge == null) return;
            DatabaseManager db = DatabaseManager.getInstance();

            String lastId = db.getBotState(LAST_DROP_KEY);
            if (lastId != null && lastId.matches("\\d{17,20}")) {
                long memberMessages = lounge.getHistoryAfter(lastId, 50).complete().getRetrievedHistory().stream()
                        .filter(m -> !m.getAuthor().isBot())
                        .count();
                if (memberMessages < MIN_MESSAGES_BETWEEN_DROPS) return;
                lounge.deleteMessageById(lastId).queue(ok -> {}, err -> {});
            }

            refreshIconIfStale();
            lounge.sendMessageEmbeds(embed()).setComponents(buttons())
                    .queue(msg -> db.setBotState(LAST_DROP_KEY, msg.getId()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static MessageEmbed embed() {
        String hook = HOOKS.get(ThreadLocalRandom.current().nextInt(HOOKS.size()));
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(255, 143, 199))
                .setAuthor("✦ AMORA GAME SPOTLIGHT ✦")
                .setTitle("🫧 " + GAME_NAME + "  ·  BETA", ROBLOX_URL)
                .setDescription("*" + hook + "*\n\n"
                        + "**" + GAME_NAME + "** is a Roblox game our friend <@" + OWL_ID + "> is helping build! "
                        + "It's in **BETA** right now, so hop in early, have fun, and share your feedback with the devs. ♡")
                .setFooter("Support our friends' creations ✨");
        String icon = iconUrl;
        if (icon != null) embed.setThumbnail(icon);
        return embed.build();
    }

    private static ActionRow buttons() {
        return ActionRow.of(
                Button.link(ROBLOX_URL, "Play on Roblox").withEmoji(Emoji.fromUnicode("🎮")),
                Button.link(DISCORD_URL, "Join their Discord").withEmoji(Emoji.fromUnicode("💬")));
    }

    /** Blocking: run on {@link #SCHEDULER}, never on a JDA event thread. Failures keep the old URL and retry next time. */
    private static void refreshIconIfStale() {
        if (Duration.between(iconFetchedAt, Instant.now()).compareTo(ICON_TTL) < 0) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ICON_API))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0 (AMORA Bot)")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            JSONArray data = new JSONObject(res.body()).optJSONArray("data");
            if (data == null || data.isEmpty()) return;
            JSONObject first = data.getJSONObject(0);
            if (!"Completed".equals(first.optString("state"))) return;
            iconUrl = first.optString("imageUrl", null);
            iconFetchedAt = Instant.now();
        } catch (Exception e) {
            System.err.println("Game Spotlight icon lookup failed: " + e.getMessage());
        }
    }
}
