package com.amore;

import java.awt.Color;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public class App {

    private static final String DAILY_SONG_CHANNEL_ID = System.getenv("DAILY_SONG_CHANNEL_ID");
    private static final int DAILY_SONG_POST_HOUR_UTC = parseUtcHour(System.getenv("DAILY_SONG_POST_HOUR_UTC"));
    private static final ScheduledExecutorService DAILY_SONG_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }

    private static int parseUtcHour(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int hour = Integer.parseInt(raw.trim());
            if (hour < 0 || hour > 23) {
                return 0;
            }
            return hour;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void main(String[] args) {
        String token = requiredEnv("DISCORD_TOKEN");

        System.out.println("✦ Starting AMORA Bot JDA Instance...");

        DatabaseManager db = DatabaseManager.getInstance();
        db.cleanupExpiredSessions();

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .setActivity(Activity.playing("AMORA ✦: Matcha Says to Type /pull"))
                    .addEventListeners(new CommandListener(), new ChatListener())
                    .build();

            jda.awaitReady();
            System.out.println("✦ AMORA Bot is officially ONLINE 24/7!");

            jda.updateCommands()
                .addCommands(
                    Commands.slash("pull", "Spend 50 Sparks to pull a random Gacha reward!"),
                    Commands.slash("balance", "Check your current AMORA Sparks balance."),
                    Commands.slash("inventory", "View your collected Gacha photocards and assets"),
                    Commands.slash("forge", "Open the Synthesis Forge to craft or recycle items"),

                    Commands.slash("leaderboard", "View the Top 10 rankings in the server")
                        .addOptions(new OptionData(OptionType.STRING, "category", "Which leaderboard to view", true)
                            .addChoice("💰 Wealth & Assets", "wealth")
                            .addChoice("🎯 Standard Directives", "bounties")
                            .addChoice("🚨 Urgent Directives", "urgent")
                        ),

                    Commands.slash("addsparks", "Admin: Add sparks to a user")
                        .addOption(OptionType.USER, "target", "The user receiving the Sparks", true)
                        .addOption(OptionType.INTEGER, "amount", "Amount of Sparks to give", true),

                    Commands.slash("award", "Admin: Publicly award Sparks for a competition or performance")
                        .addOption(OptionType.USER, "target", "The star performer", true)
                        .addOption(OptionType.INTEGER, "amount", "Sparks to award", true)
                        .addOption(OptionType.STRING, "reason", "Reason for the award", true),

                    Commands.slash("trade", "Open the trading window with another member")
                        .addOption(OptionType.USER, "target", "The member to trade with", true),

                    Commands.slash("payout", "Director Only: Award Points for completed bounties")
                        .addOption(OptionType.USER, "target", "The member receiving the payout", true)
                        .addOption(OptionType.INTEGER, "amount", "The amount of Points to award", true)
                        .addOption(OptionType.STRING, "reason", "The completed bounty or task", true),

                    Commands.slash("publish", "Director Only: Publish an item to the Forum Shop")
                        .addOption(OptionType.CHANNEL, "forum", "The Forum channel to post in", true)
                        .addOption(OptionType.STRING, "name", "Name of the asset", true)
                        .addOption(OptionType.INTEGER, "price", "Cost in Points", true)
                        .addOption(OptionType.STRING, "delivery", "The secret Link/Code to DM to the buyer", true)
                        .addOption(OptionType.STRING, "description", "Type \\n for a new line, or leave empty to use a .txt file!", false)
                        .addOption(OptionType.ATTACHMENT, "desc_file", "Upload a .txt notepad file for a detailed description", false)
                        .addOption(OptionType.ATTACHMENT, "file1", "Upload an image/GIF directly from your PC", false)
                        .addOption(OptionType.ATTACHMENT, "file2", "Upload a 2nd image/GIF directly", false)
                        .addOption(OptionType.STRING, "url1", "Or paste a web link to an image/GIF", false)
                        .addOption(OptionType.STRING, "url2", "Or paste a 2nd web link", false),

                    Commands.slash("bounty", "Manage the Forum Quest Boards")
                        .addSubcommands(
                            new SubcommandData("post", "Director Only: Post a new quest to a Bounty Forum")
                                .addOption(OptionType.CHANNEL, "forum", "The Quest Forum (Standard or Urgent)", true)
                                .addOption(OptionType.STRING, "title", "The name of the quest", true)
                                .addOption(OptionType.INTEGER, "reward", "Points reward PER PERSON", true)
                                .addOption(OptionType.STRING, "slots", "Max players (e.g. 4, 'Unlimited', 'Not Necessary')", true)
                                .addOption(OptionType.STRING, "description", "Type \\n for a new line, or leave empty to use a .txt file!", false)
                                .addOption(OptionType.ATTACHMENT, "desc_file", "Upload a .txt notepad file for a detailed description", false)
                                .addOption(OptionType.ATTACHMENT, "image", "Optional banner image for the quest card", false),

                            new SubcommandData("kick", "Director Only: Remove a freeloader from the party list")
                                .addOption(OptionType.USER, "target", "The member to kick", true),

                            new SubcommandData("cancel", "Director Only: Abort and lock a quest without paying anyone"),

                            new SubcommandData("complete", "Director Only: Run INSIDE a quest thread to mass-pay the party & lock!")
                                .addOption(OptionType.STRING, "exclude", "Tag freeloaders to EXCLUDE from payout (e.g. @troll)", false)
                        ),

                    Commands.slash("profile", "View your AMORA profile")
                        .addOption(OptionType.USER, "user", "Whose profile to view", false),

                    Commands.slash("song", "Manage the AMORA daily song pool")
                    .addSubcommands(
                    new SubcommandData("add", "Add a Spotify or YouTube song to the daily pool")
                    .addOption(OptionType.STRING, "title", "Song title", true)
                    .addOption(OptionType.STRING, "artist", "Artist name", true)
                    .addOption(OptionType.STRING, "link", "Spotify or YouTube link", true),

                    new SubcommandData("importplaylist", "Import all songs from a public YouTube playlist")
                    .addOption(OptionType.STRING, "link", "Public YouTube playlist URL", true),

                    new SubcommandData("remove", "Remove a song by ID (owner or admin)")
                    .addOption(OptionType.INTEGER, "id", "Song ID from /song list", true),

                    new SubcommandData("list", "Show recent songs in the AMORA pool"),
                    new SubcommandData("suggest", "Get a random song recommendation from the pool"),
                    new SubcommandData("postnow", "Admin: force-post a song recommendation now")
                    )
                ).queue();

            System.out.println("✦ Slash commands registered successfully.");
            startDailySongScheduler(jda);

        } catch (Exception e) {
            System.out.println("❌ Critical failure during bot initialization.");
            e.printStackTrace();
        }
    }

    private static void startDailySongScheduler(JDA jda) {
        DAILY_SONG_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                attemptAutomaticDailySongPost(jda);
            } catch (Exception e) {
                System.out.println("❌ Daily song scheduler error.");
                e.printStackTrace();
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    private static void attemptAutomaticDailySongPost(JDA jda) {
        if (DAILY_SONG_CHANNEL_ID == null || DAILY_SONG_CHANNEL_ID.isBlank()) {
            return;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);

        if (nowUtc.getHour() < DAILY_SONG_POST_HOUR_UTC) {
            return;
        }

        String todayUtc = LocalDate.now(ZoneOffset.UTC).toString();
        String lastPostedDate = db.getBotState("daily_song_last_post_date");

        if (todayUtc.equals(lastPostedDate)) {
            return;
        }

        postSongRecommendation(jda, true);
    }

    public static boolean postSongRecommendation(JDA jda, boolean lockForToday) {
        if (DAILY_SONG_CHANNEL_ID == null || DAILY_SONG_CHANNEL_ID.isBlank()) {
            return false;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        DatabaseManager.SongSuggestionRecord song = db.getRandomActiveSongSuggestion();
        if (song == null) {
            return false;
        }

        TextChannel channel = jda.getTextChannelById(DAILY_SONG_CHANNEL_ID);
        if (channel == null) {
            return false;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(255, 105, 180))
                .setTitle("✦ AMORA SONG OF THE DAY ✦")
                .setDescription(
                        "🎶 **" + song.title + "**\n" +
                        "by **" + song.artist + "**\n\n" +
                        "🎧 **Listen here:**\n" + song.link + "\n\n" +
                        "🫶 **Suggested by:** <@" + song.addedBy + ">"
                )
                .addField("Source", song.source, true)
                .addField("Song ID", "#" + song.songId, true)
                .setFooter("AMORA Daily Recommendation • Curated by the community", null);

        String artworkUrl = CommandListener.fetchSongArtwork(song.link);
        if (artworkUrl != null && !artworkUrl.isBlank()) {
            embed.setImage(artworkUrl);
        }

        try {
            channel.sendMessageEmbeds(embed.build()).complete();
            db.markSongFeatured(song.songId, System.currentTimeMillis());

            if (lockForToday) {
                db.setBotState("daily_song_last_post_date", LocalDate.now(ZoneOffset.UTC).toString());
            }

            return true;
        } catch (Exception e) {
            System.out.println("❌ Failed to post daily song recommendation.");
            e.printStackTrace();
            return false;
        }
    }
}