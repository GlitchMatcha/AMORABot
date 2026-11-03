package com.amore;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;

/** Daily Song of the Day: selection, voting, listening streaks, vinyls, sponsorships, thread rewards, recaps. */
public final class MusicService {

    private MusicService() {
    }

    static final Color PINK = new Color(255, 105, 180);

    static final String DAILY_CHANNEL_ID = env("DAILY_SONG_CHANNEL_ID");
    static final String LOUNGE_CHANNEL_ID = env("LOUNGE_CHANNEL_ID");
    static final String STREAK_ROLE_ID = env("MUSIC_STREAK_ROLE_ID");
    static final String STREAK_ELITE_ROLE_ID = env("MUSIC_STREAK_ELITE_ROLE_ID");
    static final int POST_HOUR_UTC = parseHour(System.getenv("DAILY_SONG_POST_HOUR_UTC"));

    static final int SPONSOR_COST = 150;
    static final long SPONSOR_COOLDOWN_MS = TimeUnit.DAYS.toMillis(7);
    static final int CRATE_COST = 40;
    static final int CRATE_DUPLICATE_REFUND = 10;
    static final int THREAD_REWARD_SLOTS = 5;
    static final int THREAD_REWARD_SPARKS = 2;
    static final long FEATURE_LIFETIME_MS = TimeUnit.HOURS.toMillis(26);
    static final int STREAK_ROLE_DAYS = 7;
    static final int STREAK_ELITE_DAYS = 30;

    static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2);
    static final ExecutorService WORKERS = Executors.newFixedThreadPool(4);

    /** thread id -> feature key ("" = not a song thread). Bounded; cleared when full. */
    private static final Map<String, String> THREAD_FEATURES = new ConcurrentHashMap<>();

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null) return null;
        v = v.trim().replaceAll("^\"|\"$", "");
        return v.isBlank() ? null : v;
    }

    static int parseHour(String raw) {
        try {
            int hour = Integer.parseInt(raw == null ? "" : raw.trim());
            return hour >= 0 && hour <= 23 ? hour : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static MusicRepository repo() {
        return MusicRepository.get();
    }

    static DatabaseManager db() {
        return DatabaseManager.getInstance();
    }

    static LocalDate todayUtc() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    // ================================================================ themes

    public enum Theme {
        MELLOW_MONDAY(DayOfWeek.MONDAY, "chill", "🌙 Mellow Monday"),
        UNDERRATED_WEDNESDAY(DayOfWeek.WEDNESDAY, "underrated", "💎 Underrated Wednesday"),
        THROWBACK_THURSDAY(DayOfWeek.THURSDAY, "throwback", "📼 Throwback Thursday"),
        HYPE_FRIDAY(DayOfWeek.FRIDAY, "hype", "⚡ Hype Friday"),
        SOFT_SUNDAY(DayOfWeek.SUNDAY, "acoustic", "☁️ Soft Sunday");

        final DayOfWeek day;
        final String tag;
        final String label;

        Theme(DayOfWeek day, String tag, String label) {
            this.day = day;
            this.tag = tag;
            this.label = label;
        }

        static Theme forDay(DayOfWeek day) {
            for (Theme t : values()) if (t.day == day) return t;
            return null;
        }
    }

    // ================================================================ lifecycle

    public static void start(JDA jda) {
        MusicRepository.get(); // run schema migration at boot, not on first interaction
        SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                tick(jda);
            } catch (Throwable t) {
                System.err.println("Music scheduler error: " + t.getMessage());
                t.printStackTrace();
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    private static void tick(JDA jda) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        settleExpiredFeatures(jda);

        if (DAILY_CHANNEL_ID != null && now.getHour() >= POST_HOUR_UTC) {
            String today = now.toLocalDate().toString();
            if (!today.equals(db().getBotState("daily_song_last_post_date"))) {
                postDaily(jda, true);
            }
        }

        MusicBattle.tick(jda);

        if (now.getDayOfMonth() == 1 && now.getHour() >= POST_HOUR_UTC) {
            YearMonth previous = YearMonth.from(now).minusMonths(1);
            String key = "music_recap_" + previous;
            if (db().getBotState(key) == null && postRecap(jda, previous)) {
                db().setBotState(key, "posted");
            }
        }
    }

    // ================================================================ daily post

    /** Posts a Song of the Day. {@code lockForToday=false} is a staff bonus drop that does not replace the scheduled one. */
    public static synchronized boolean postDaily(JDA jda, boolean lockForToday) {
        if (DAILY_CHANNEL_ID == null) return false;
        TextChannel channel = jda.getTextChannelById(DAILY_CHANNEL_ID);
        if (channel == null) return false;

        LocalDate today = todayUtc();
        Theme theme = Theme.forDay(today.getDayOfWeek());

        MusicRepository.Sponsor sponsor = null;
        MusicRepository.Song song = null;
        for (MusicRepository.Sponsor s : repo().pendingSponsors()) {
            MusicRepository.Song candidate = repo().songById(s.songId());
            if (candidate != null && candidate.active()) {
                sponsor = s;
                song = candidate;
                break;
            }
        }
        boolean themed = false;
        if (song == null && theme != null) {
            song = pickWeighted(theme.tag);
            themed = song != null;
        }
        if (song == null) song = pickWeighted(null);
        if (song == null) return false;

        settleBefore(jda, System.currentTimeMillis());

        long now = System.currentTimeMillis();
        String key = lockForToday ? today.toString() : today + "-bonus-" + (now / 1000);
        if (lockForToday && repo().feature(key) != null) {
            db().setBotState("daily_song_last_post_date", today.toString());
            return false;
        }

        String themeLabel = themed ? theme.label : null;
        EmbedBuilder embed = dailyEmbed(song, themeLabel, sponsor, lockForToday);
        String artwork = MusicArt.artworkUrl(song.link());
        if (artwork != null) embed.setImage(artwork);

        Message message;
        try {
            message = channel.sendMessageEmbeds(embed.build())
                    .setComponents(dailyRows(key, song, new MusicRepository.Tally(0, 0, 0), false))
                    .complete();
        } catch (Exception e) {
            System.out.println("❌ Failed to post daily song recommendation.");
            e.printStackTrace();
            return false;
        }

        repo().insertFeature(new MusicRepository.Feature(key, today.toString(), song.songId(), channel.getId(),
                message.getId(), null, themeLabel, sponsor == null ? null : sponsor.userId(), false, now));
        repo().markFeatured(song.songId(), now);
        if (sponsor != null) repo().closeSponsor(sponsor.id(), "posted", key);
        if (lockForToday) db().setBotState("daily_song_last_post_date", today.toString());

        final MusicRepository.Song posted = song;
        message.createThreadChannel(SongMetadata.truncate("🎧 " + song.displayTitle() + " — " + song.displayArtist(), 95))
                .queue(thread -> {
                    repo().setFeatureThread(key, thread.getId());
                    rememberThread(thread.getId(), key);
                    thread.sendMessage("💬 **What do you think of this one?**\nThe first **" + THREAD_REWARD_SLOTS
                            + "** thoughtful replies here earn `+" + THREAD_REWARD_SPARKS + " Sparks` each.").queue();
                }, err -> System.err.println("Could not open song thread: " + err.getMessage()));

        if (LOUNGE_CHANNEL_ID != null) {
            TextChannel lounge = jda.getTextChannelById(LOUNGE_CHANNEL_ID);
            if (lounge != null) {
                lounge.sendMessage("🎶 ✦ **A new Song of the Day just dropped!** ✦\nHead over to <#" + DAILY_CHANNEL_ID
                        + "> to rate **" + posted.displayTitle() + "** by **" + posted.displayArtist()
                        + "** and keep your 🎧 listening streak alive! ✨").queue();
            }
        }
        return true;
    }

    private static EmbedBuilder dailyEmbed(MusicRepository.Song song, String themeLabel, MusicRepository.Sponsor sponsor,
                                           boolean scheduled) {
        StringBuilder desc = new StringBuilder();
        if (themeLabel != null) desc.append("**").append(themeLabel).append("**\n\n");
        desc.append("🎶 **").append(song.displayTitle()).append("**\n")
                .append("by **").append(song.displayArtist()).append("**\n\n")
                .append("🫶 **Suggested by:** ").append(song.creditLine()).append("\n");
        if (sponsor != null) desc.append("💸 **Sponsored by:** <@").append(sponsor.userId()).append(">\n");
        desc.append("\nRate it below, then tap **🎧 Listened** to keep your streak and collect today's vinyl.");

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(PINK)
                .setTitle(scheduled ? "✦ AMORA SONG OF THE DAY ✦" : "✦ AMORA BONUS SONG DROP ✦")
                .setDescription(desc.toString())
                .addField("Source", song.source(), true)
                .addField("Song ID", "#" + song.songId(), true)
                .setFooter("AMORA Daily Recommendation • Voting closes when the next song drops", null);
        List<String> tags = SongMetadata.tagList(song.tags());
        if (!tags.isEmpty()) eb.addField("Tags", "`" + String.join("` `", tags) + "`", true);
        return eb;
    }

    static List<ActionRow> dailyRows(String key, MusicRepository.Song song, MusicRepository.Tally t, boolean closed) {
        ActionRow votes = ActionRow.of(
                Button.secondary("music:vote:" + key + ":" + MusicRepository.VOTE_FIRE, String.valueOf(t.fire()))
                        .withEmoji(Emoji.fromUnicode("🔥")).withDisabled(closed),
                Button.secondary("music:vote:" + key + ":" + MusicRepository.VOTE_LIKE, String.valueOf(t.like()))
                        .withEmoji(Emoji.fromUnicode("🙂")).withDisabled(closed),
                Button.secondary("music:vote:" + key + ":" + MusicRepository.VOTE_MEH, String.valueOf(t.meh()))
                        .withEmoji(Emoji.fromUnicode("💤")).withDisabled(closed));
        ActionRow actions = ActionRow.of(
                Button.success("music:listen:" + key, "Listened").withEmoji(Emoji.fromUnicode("🎧")).withDisabled(closed),
                Button.link(song.link(), "Open song").withEmoji(Emoji.fromUnicode("▶️")));
        return List.of(votes, actions);
    }

    /** Weighted pick among the 25 least recently featured songs: well-rated and never-featured songs are favoured. */
    static MusicRepository.Song pickWeighted(String tag) {
        List<MusicRepository.Song> candidates = repo().leastRecentlyFeatured(tag, 25);
        if (candidates.isEmpty()) return null;
        Map<Integer, MusicRepository.Tally> tallies = repo().songTallies();
        double[] weights = new double[candidates.size()];
        double sum = 0;
        for (int i = 0; i < candidates.size(); i++) {
            MusicRepository.Song s = candidates.get(i);
            MusicRepository.Tally t = tallies.get(s.songId());
            double w = (t != null && t.total() >= 2) ? 2.0 + t.average() : 2.0; // 1.0 .. 4.0
            if (s.lastFeaturedAt() == 0) w *= 1.5;
            weights[i] = w;
            sum += w;
        }
        double roll = ThreadLocalRandom.current().nextDouble(sum);
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    // ================================================================ voting & listening

    public static void handleVote(ButtonInteractionEvent event, String key, int vote) {
        event.deferReply(true).queue();
        MusicRepository.Feature feature = repo().feature(key);
        if (feature == null) {
            event.getHook().sendMessage("  This song drop no longer exists.").queue();
            return;
        }
        if (feature.settled()) {
            event.getHook().sendMessage("  Voting on this drop has closed. Catch the next Song of the Day!").queue();
            return;
        }
        MusicRepository.Song song = repo().songById(feature.songId());
        if (song == null) {
            event.getHook().sendMessage("  That song was removed from the pool.").queue();
            return;
        }
        String userId = event.getUser().getId();
        if (song.isMemberPick() && song.addedBy().equals(userId)) {
            event.getHook().sendMessage("  You suggested this one, so your vote stays out of it 😉").queue();
            return;
        }
        repo().upsertVote(key, userId, song.songId(), vote);
        MusicRepository.Tally tally = repo().featureTally(key);
        event.getMessage().editMessageComponents(dailyRows(key, song, tally, false)).queue(s -> {}, e -> {});
        String label = vote == MusicRepository.VOTE_FIRE ? "🔥 Banger" : vote == MusicRepository.VOTE_LIKE ? "🙂 Nice" : "💤 Not for me";
        event.getHook().sendMessage("Vote saved: **" + label + "**. You can change it until the next drop.").queue();
    }

    public static void handleListen(ButtonInteractionEvent event, String key) {
        event.deferReply(true).queue();
        MusicRepository.Feature feature = repo().feature(key);
        if (feature == null || System.currentTimeMillis() - feature.postedAt() > FEATURE_LIFETIME_MS) {
            event.getHook().sendMessage("  This drop has ended. Catch today's Song of the Day instead!").queue();
            return;
        }
        String userId = event.getUser().getId();
        LocalDate today = todayUtc();
        if (!repo().recordListen(key, userId, today.toString())) {
            event.getHook().sendMessage("🎧 You already logged this one. Come back for the next drop!").queue();
            return;
        }

        MusicRepository.Stats stats = repo().stats(userId);
        int streak;
        if (today.toString().equals(stats.lastListenDate())) {
            streak = stats.streak(); // bonus drop on the same day
        } else if (today.minusDays(1).toString().equals(stats.lastListenDate())) {
            streak = stats.streak() + 1;
        } else {
            streak = 1;
        }
        int best = Math.max(stats.bestStreak(), streak);
        boolean newDay = !today.toString().equals(stats.lastListenDate());
        repo().saveStreak(userId, streak, best, today.toString());

        int sparks = 1 + (newDay && streak % 7 == 0 ? 10 : 0);
        db().addSparks(userId, sparks);

        int copies = repo().addVinyl(userId, feature.songId());
        MusicRepository.Song song = repo().songById(feature.songId());
        String rarity = rarityOf(repo().songTally(feature.songId()));

        if (newDay && event.getGuild() != null) grantStreakRoles(event.getGuild(), userId, streak);

        StringBuilder msg = new StringBuilder("🎧 **Listen logged!** `+" + sparks + " Spark" + (sparks == 1 ? "" : "s") + "`\n");
        msg.append("🔥 Streak: **").append(streak).append(" day").append(streak == 1 ? "" : "s").append("** (best ").append(best).append(")\n");
        if (newDay && streak % 7 == 0) msg.append("🏅 **").append(streak).append("-day milestone bonus!**\n");
        if (song != null) {
            msg.append("💿 Vinyl: **").append(song.displayTitle()).append("** · ").append(rarity)
                    .append(copies > 1 ? " (copy x" + copies + ")" : " **NEW!**").append("\n");
        }
        msg.append("-# See your collection with `/music vinyls`.");
        event.getHook().sendMessage(msg.toString()).queue();
    }

    private static void grantStreakRoles(Guild guild, String userId, int streak) {
        long refreshUntil = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2);
        grantTimedRole(guild, userId, streak >= STREAK_ROLE_DAYS ? STREAK_ROLE_ID : null, refreshUntil);
        grantTimedRole(guild, userId, streak >= STREAK_ELITE_DAYS ? STREAK_ELITE_ROLE_ID : null, refreshUntil);
    }

    /** Adds the role and (re)schedules its removal; a lapsed streak lets RoleManager expire it. */
    static void grantTimedRole(Guild guild, String userId, String roleId, long expiresAt) {
        if (roleId == null) return;
        Role role = guild.getRoleById(roleId);
        if (role == null) return;
        guild.retrieveMemberById(userId).queue(member -> {
            if (!member.getRoles().contains(role)) guild.addRoleToMember(member, role).queue(s -> {}, e -> {});
            db().scheduleRoleRemoval(userId, roleId, expiresAt);
        }, err -> {});
    }

    static String rarityOf(MusicRepository.Tally t) {
        if (t.total() < 3) return "⚪ Common";
        double avg = t.average();
        if (avg >= 1.5) return "🌟 Legendary";
        if (avg >= 1.0) return "💜 Epic";
        if (avg >= 0.5) return "💙 Rare";
        return "⚪ Common";
    }

    private static int rarityRank(String rarity) {
        if (rarity.contains("Legendary")) return 3;
        if (rarity.contains("Epic")) return 2;
        if (rarity.contains("Rare")) return 1;
        return 0;
    }

    // ================================================================ settlement

    private static void settleExpiredFeatures(JDA jda) {
        settleBefore(jda, System.currentTimeMillis() - FEATURE_LIFETIME_MS);
    }

    private static void settleBefore(JDA jda, long postedBefore) {
        for (MusicRepository.Feature f : repo().unsettledFeaturesBefore(postedBefore)) settle(jda, f);
    }

    /** Closes voting, pays the curator, and posts the result in the song's thread. */
    private static void settle(JDA jda, MusicRepository.Feature feature) {
        if (!repo().markSettled(feature.featureKey())) return;
        MusicRepository.Song song = repo().songById(feature.songId());
        if (song == null) return;

        MusicRepository.Tally all = repo().featureTally(feature.featureKey());
        StringBuilder result = new StringBuilder("📊 **Final results for " + song.displayTitle() + "**\n🔥 `" + all.fire()
                + "`  🙂 `" + all.like() + "`  💤 `" + all.meh() + "`");

        if (song.isMemberPick()) {
            MusicRepository.Tally counted = repo().featureTallyExcluding(feature.featureKey(), song.addedBy());
            int reward = Math.min(40, 5 + 2 * counted.fire() + counted.like());
            int points = Math.max(0, counted.score()) + 3;
            db().addSparks(song.addedBy(), reward);
            repo().addCuratorPoints(song.addedBy(), points, 1);
            result.append("\n🫶 <@").append(song.addedBy()).append("> earned `+").append(reward)
                    .append(" Sparks` and `+").append(points).append(" Curator Points` for this pick!");
        }

        if (feature.channelId() != null && feature.messageId() != null) {
            TextChannel channel = jda.getTextChannelById(feature.channelId());
            if (channel != null) {
                channel.editMessageComponentsById(feature.messageId(), dailyRows(feature.featureKey(), song, all, true))
                        .queue(s -> {}, e -> {});
            }
        }
        if (feature.threadId() != null) {
            ThreadChannel thread = jda.getThreadChannelById(feature.threadId());
            if (thread != null) thread.sendMessage(result.toString()).queue(s -> {}, e -> {});
        }
    }

    // ================================================================ thread replies

    private static void rememberThread(String threadId, String key) {
        if (THREAD_FEATURES.size() > 5000) THREAD_FEATURES.clear();
        THREAD_FEATURES.put(threadId, key);
    }

    /** Rewards the first thoughtful replies in a Song of the Day thread. Cheap no-op for every other channel. */
    public static void onThreadMessage(MessageReceivedEvent event) {
        if (!(event.getChannel() instanceof ThreadChannel thread)) return;
        if (DAILY_CHANNEL_ID == null || !DAILY_CHANNEL_ID.equals(thread.getParentChannel().getId())) return;

        String key = THREAD_FEATURES.get(thread.getId());
        if (key == null) {
            MusicRepository.Feature f = repo().featureByThread(thread.getId());
            key = f == null ? "" : f.featureKey();
            rememberThread(thread.getId(), key);
        }
        if (key.isEmpty()) return;

        String content = event.getMessage().getContentRaw().trim();
        if (content.length() < 12 || content.split("\\s+").length < 3 || !content.matches("(?s).*\\p{L}.*")) return;

        MusicRepository.Feature feature = repo().feature(key);
        if (feature == null || feature.settled()) return;
        if (repo().countThreadRewards(key) >= THREAD_REWARD_SLOTS) return;
        if (!repo().claimThreadReward(key, event.getAuthor().getId())) return;

        db().addSparks(event.getAuthor().getId(), THREAD_REWARD_SPARKS);
        event.getMessage().addReaction(Emoji.fromUnicode("✨")).queue(s -> {}, e -> {});
    }

    // ================================================================ sponsorship

    public static String sponsor(String userId, int songId) {
        MusicRepository.Song song = repo().songById(songId);
        if (song == null || !song.active()) return "  That song ID is not in the active pool.";
        if (repo().songHasPendingSponsor(songId)) return "  That song is already sponsored and waiting in the queue.";

        MusicRepository.Sponsor last = repo().latestSponsorBy(userId);
        if (last != null && "pending".equals(last.status())) {
            return "  You already have a sponsored song waiting. One at a time!";
        }
        if (last != null && System.currentTimeMillis() - last.createdAt() < SPONSOR_COOLDOWN_MS) {
            long ready = (last.createdAt() + SPONSOR_COOLDOWN_MS) / 1000;
            return "  You can sponsor again <t:" + ready + ":R>.";
        }
        if (db().trySpendSparks(userId, SPONSOR_COST) < 0) {
            return "  Sponsoring costs **" + SPONSOR_COST + " Sparks**.";
        }
        MusicRepository.Sponsor created = repo().insertSponsor(songId, userId, SPONSOR_COST);
        if (created == null) {
            db().addSparks(userId, SPONSOR_COST);
            return "  Could not save your sponsorship. You were refunded.";
        }
        int position = 0;
        for (MusicRepository.Sponsor s : repo().pendingSponsors()) {
            position++;
            if (s.id() == created.id()) break;
        }
        return "💸 **Sponsored!** **" + song.displayTitle() + "** by **" + song.displayArtist() + "** is #" + position
                + " in the queue and will be a coming Song of the Day. `-" + SPONSOR_COST + " Sparks`";
    }

    /** Refunds pending sponsorships of a song that is leaving the pool. */
    public static void refundSponsors(int songId) {
        for (MusicRepository.Sponsor s : repo().pendingSponsorsForSong(songId)) {
            if (repo().closeSponsor(s.id(), "refunded", null)) db().addSparks(s.userId(), s.cost());
        }
    }

    // ================================================================ vinyls

    public static String openCrate(String userId) {
        if (repo().countFeaturedSongs() == 0) return "  No songs have been featured yet, so the crate is empty.";
        if (db().trySpendSparks(userId, CRATE_COST) < 0) return "  A vinyl crate costs **" + CRATE_COST + " Sparks**.";
        MusicRepository.Song song = repo().randomFeaturedSong();
        if (song == null) {
            db().addSparks(userId, CRATE_COST);
            return "  The crate was empty. You were refunded.";
        }
        int copies = repo().addVinyl(userId, song.songId());
        String rarity = rarityOf(repo().songTally(song.songId()));
        if (copies > 1) {
            db().addSparks(userId, CRATE_DUPLICATE_REFUND);
            return "📦 You opened a crate: 💿 **" + song.displayTitle() + "** · " + rarity + " (duplicate x" + copies
                    + ", `+" + CRATE_DUPLICATE_REFUND + " Sparks` back)";
        }
        return "📦 You opened a crate: 💿 **" + song.displayTitle() + "** by " + song.displayArtist() + " · " + rarity + " **NEW!**";
    }

    public static EmbedBuilder vinylCollection(User user) {
        List<MusicRepository.Vinyl> vinyls = repo().vinyls(user.getId());
        int featured = repo().countFeaturedSongs();
        Map<Integer, MusicRepository.Tally> tallies = repo().songTallies();

        record Entry(String line, int rank) {
        }
        List<Entry> entries = new ArrayList<>();
        for (MusicRepository.Vinyl v : vinyls) {
            MusicRepository.Song s = repo().songById(v.songId());
            if (s == null) continue;
            String rarity = rarityOf(tallies.getOrDefault(v.songId(), new MusicRepository.Tally(0, 0, 0)));
            entries.add(new Entry(rarity + " · **" + SongMetadata.truncate(s.displayTitle(), 40) + "** — "
                    + SongMetadata.truncate(s.displayArtist(), 24) + (v.copies() > 1 ? " x" + v.copies() : ""), rarityRank(rarity)));
        }
        entries.sort(Comparator.comparingInt(Entry::rank).reversed());

        StringBuilder desc = new StringBuilder();
        if (entries.isEmpty()) {
            desc.append("_No vinyls yet._ Tap **🎧 Listened** on the Song of the Day, or open a `/music crate`.");
        } else {
            int shown = 0;
            for (Entry e : entries) {
                if (desc.length() > 3600 || shown >= 25) {
                    desc.append("\n…and ").append(entries.size() - shown).append(" more");
                    break;
                }
                desc.append(e.line()).append("\n");
                shown++;
            }
        }
        return new EmbedBuilder()
                .setColor(PINK)
                .setTitle("💿 " + user.getEffectiveName() + "'s Vinyl Shelf")
                .setDescription(desc.toString())
                .addField("Collection", "`" + entries.size() + " / " + featured + "` featured songs", true)
                .setThumbnail(user.getEffectiveAvatarUrl())
                .setFooter("Rarity follows how the community rated each song", null);
    }

    // ================================================================ stats, profile, twin

    public static String profileField(String userId) {
        MusicRepository.Stats s = repo().stats(userId);
        int added = repo().songsAddedBy(userId).size();
        int vinyls = repo().vinyls(userId).size();
        return "**Songs Added:** `" + added + "` · **Featured:** `" + s.songsFeatured() + "`\n"
                + "**Curator Points:** `" + s.curatorPoints() + "`\n"
                + "**Listening Streak:** `" + s.streak() + "` (best `" + s.bestStreak() + "`)\n"
                + "**Vinyls:** `" + vinyls + "` · **Music Game Wins:** `" + s.gameWins() + "`";
    }

    public static EmbedBuilder statsEmbed(User user) {
        MusicRepository.Stats s = repo().stats(user.getId());
        List<MusicRepository.Song> added = repo().songsAddedBy(user.getId());
        Map<Integer, MusicRepository.Tally> tallies = repo().songTallies();

        MusicRepository.Song bestSong = null;
        int bestScore = Integer.MIN_VALUE;
        for (MusicRepository.Song song : added) {
            MusicRepository.Tally t = tallies.get(song.songId());
            if (t != null && t.score() > bestScore) {
                bestScore = t.score();
                bestSong = song;
            }
        }
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(PINK)
                .setTitle("🎵 " + user.getEffectiveName() + "'s Music Stats")
                .setDescription(profileField(user.getId()))
                .addField("Higher or Lower best", "`" + s.hlBest() + "` in a row", true)
                .setThumbnail(user.getEffectiveAvatarUrl());
        if (bestSong != null) {
            eb.addField("Best-rated pick", "**" + bestSong.displayTitle() + "** — " + bestSong.displayArtist()
                    + " (score " + bestScore + ")", false);
        }
        return eb;
    }

    /** Taste similarity in [0, 100] from shared song votes, or -1 with fewer than 3 shared songs. */
    static int similarity(Map<Integer, Double> a, Map<Integer, Double> b) {
        int shared = 0;
        double diff = 0;
        for (Map.Entry<Integer, Double> e : a.entrySet()) {
            Double other = b.get(e.getKey());
            if (other == null) continue;
            shared++;
            diff += Math.abs(e.getValue() - other);
        }
        if (shared < 3) return -1;
        return (int) Math.round(100 * (1 - (diff / shared) / 3.0));
    }

    private static Map<String, Map<Integer, Double>> tasteProfiles() {
        Map<String, Map<Integer, double[]>> sums = new HashMap<>();
        for (MusicRepository.VoteRow v : repo().allVotes()) {
            double[] acc = sums.computeIfAbsent(v.userId(), k -> new HashMap<>()).computeIfAbsent(v.songId(), k -> new double[2]);
            acc[0] += v.vote();
            acc[1]++;
        }
        Map<String, Map<Integer, Double>> out = new HashMap<>();
        sums.forEach((user, songs) -> {
            Map<Integer, Double> avg = new HashMap<>();
            songs.forEach((song, acc) -> avg.put(song, acc[0] / acc[1]));
            out.put(user, avg);
        });
        return out;
    }

    public static String twin(String userId, String otherId) {
        Map<String, Map<Integer, Double>> profiles = tasteProfiles();
        Map<Integer, Double> mine = profiles.get(userId);
        if (mine == null || mine.size() < 3) {
            return "  Rate at least **3** Songs of the Day first so I can read your taste!";
        }
        if (otherId != null) {
            Map<Integer, Double> theirs = profiles.get(otherId);
            int sim = theirs == null ? -1 : similarity(mine, theirs);
            if (sim < 0) return "  You two need at least **3** rated songs in common. Keep voting!";
            return "💞 <@" + userId + "> × <@" + otherId + ">: **" + sim + "% music match** " + matchFlavor(sim);
        }
        String bestUser = null;
        int best = -1;
        for (Map.Entry<String, Map<Integer, Double>> e : profiles.entrySet()) {
            if (e.getKey().equals(userId)) continue;
            int sim = similarity(mine, e.getValue());
            if (sim > best) {
                best = sim;
                bestUser = e.getKey();
            }
        }
        if (bestUser == null || best < 0) return "  Nobody shares 3+ rated songs with you yet. Keep voting!";
        return "👯 Your music twin is <@" + bestUser + "> with a **" + best + "% match** " + matchFlavor(best);
    }

    private static String matchFlavor(int sim) {
        if (sim >= 90) return "— basically the same playlist 🫶";
        if (sim >= 75) return "— great taste in common ✨";
        if (sim >= 50) return "— some overlap, some debates 🎧";
        return "— opposites attract? 😅";
    }

    // ================================================================ monthly recap

    /** Posts the recap card for a month into the daily song channel. */
    public static boolean postRecap(JDA jda, YearMonth month) {
        if (DAILY_CHANNEL_ID == null) return false;
        TextChannel channel = jda.getTextChannelById(DAILY_CHANNEL_ID);
        if (channel == null) return false;
        Recap recap = buildRecap(jda, month);
        try {
            channel.sendMessageEmbeds(recap.embed.build())
                    .addFiles(FileUpload.fromData(recap.card, "music_recap.png"))
                    .complete();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    record Recap(EmbedBuilder embed, byte[] card) {
    }

    static Recap buildRecap(JDA jda, YearMonth month) {
        long from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<MusicRepository.Feature> features = repo().featuresPostedBetween(from, to);

        Map<Integer, int[]> songTotals = new LinkedHashMap<>(); // songId -> [score, votes]
        Map<String, Integer> curatorScores = new HashMap<>();
        for (MusicRepository.Feature f : features) {
            MusicRepository.Tally t = repo().featureTally(f.featureKey());
            int[] acc = songTotals.computeIfAbsent(f.songId(), k -> new int[2]);
            acc[0] += t.score();
            acc[1] += t.total();
            MusicRepository.Song s = repo().songById(f.songId());
            if (s != null && s.isMemberPick()) curatorScores.merge(s.addedBy(), t.score(), Integer::sum);
        }
        List<Map.Entry<Integer, int[]>> ranked = new ArrayList<>(songTotals.entrySet());
        ranked.removeIf(e -> e.getValue()[1] == 0);
        ranked.sort((a, b) -> a.getValue()[0] != b.getValue()[0]
                ? Integer.compare(b.getValue()[0], a.getValue()[0])
                : Integer.compare(b.getValue()[1], a.getValue()[1]));

        List<MusicArt.RecapLine> topLines = new ArrayList<>();
        StringBuilder topText = new StringBuilder();
        MusicRepository.Song hero = null;
        for (int i = 0; i < ranked.size() && i < 5; i++) {
            MusicRepository.Song s = repo().songById(ranked.get(i).getKey());
            if (s == null) continue;
            if (hero == null) hero = s;
            int[] v = ranked.get(i).getValue();
            topLines.add(new MusicArt.RecapLine(s.displayTitle(), s.displayArtist() + " · score " + v[0] + " · " + v[1] + " votes"));
            topText.append("`#").append(i + 1).append("` **").append(s.displayTitle()).append("** — ").append(s.displayArtist())
                    .append(" (score ").append(v[0]).append(")\n");
        }

        String topCurator = curatorScores.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);

        String fromDate = month.atDay(1).toString();
        String toDate = month.atEndOfMonth().toString();
        String streakUser = null;
        int streakLen = 0;
        String prevUser = null;
        LocalDate prevDate = null;
        int run = 0;
        for (MusicRepository.ListenRow row : repo().listensBetweenDates(fromDate, toDate)) {
            LocalDate d = LocalDate.parse(row.date());
            run = row.userId().equals(prevUser) && prevDate != null && prevDate.plusDays(1).equals(d) ? run + 1 : 1;
            if (run > streakLen) {
                streakLen = run;
                streakUser = row.userId();
            }
            prevUser = row.userId();
            prevDate = d;
        }

        int votes = repo().countVotesBetween(from, to);
        int listens = repo().countListensBetweenDates(fromDate, toDate);

        List<MusicArt.RecapLine> highlights = List.of(
                new MusicArt.RecapLine("Top Curator", topCurator == null ? "—" : userName(jda, topCurator)),
                new MusicArt.RecapLine("Longest Streak", streakUser == null ? "—" : userName(jda, streakUser) + " · " + streakLen + "d"),
                new MusicArt.RecapLine("Votes Cast", String.valueOf(votes)),
                new MusicArt.RecapLine("Listens", String.valueOf(listens)));

        BufferedImage heroArt = hero == null ? null : MusicArt.artwork(hero.link());
        String period = month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + month.getYear();
        byte[] card = MusicArt.recapCard("Monthly Music Recap", period, topLines, highlights, heroArt);

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(PINK)
                .setTitle("✦ AMORA MUSIC RECAP · " + period.toUpperCase() + " ✦")
                .setDescription(topText.length() == 0 ? "_No rated songs this month._" : topText.toString())
                .addField("🫶 Top Curator", topCurator == null ? "—" : "<@" + topCurator + ">", true)
                .addField("🔥 Longest Streak", streakUser == null ? "—" : "<@" + streakUser + "> · " + streakLen + " days", true)
                .addField("📊 Activity", "`" + features.size() + "` drops · `" + votes + "` votes · `" + listens + "` listens", false)
                .setImage("attachment://music_recap.png")
                .setFooter("AMORA Music • " + Instant.ofEpochMilli(to - 1).atZone(ZoneOffset.UTC).toLocalDate(), null);
        return new Recap(embed, card);
    }

    static String userName(JDA jda, String userId) {
        try {
            User u = jda.retrieveUserById(userId).complete();
            return u.getEffectiveName();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    static boolean isStaff(Member member, String rawRoleIds) {
        if (member == null) return false;
        if (member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) return true;
        if (rawRoleIds == null || rawRoleIds.isBlank()) return false;
        Set<String> allowed = Set.of(rawRoleIds.replace(" ", "").split(","));
        return member.getRoles().stream().anyMatch(r -> allowed.contains(r.getId()));
    }
}
