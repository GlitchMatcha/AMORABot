package com.amore;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;

/** Music minigames: Who Added It?, Blurred Cover, daily Songdle, and Higher or Lower. */
public final class MusicGames {

    private MusicGames() {
    }

    private static final Color GAME_COLOR = new Color(186, 85, 211);

    /** Channels with a running public music game (one at a time per channel). */
    private static final Set<String> BUSY_CHANNELS = ConcurrentHashMap.newKeySet();

    private static MusicRepository repo() {
        return MusicRepository.get();
    }

    private static DatabaseManager db() {
        return DatabaseManager.getInstance();
    }

    /** Lounge rotation entry: spawns a random public music game, or runs {@code fallback} if none can start. */
    public static void spawnRandomLoungeGame(MessageChannel channel, Runnable fallback) {
        boolean who = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
        MusicService.WORKERS.execute(() -> {
            String err = who ? startWhoAdded(channel) : startCover(channel);
            if (err != null) fallback.run();
        });
    }

    // ================================================================ Who Added It?

    static final int WHO_REWARD = 8;
    static final long WHO_DURATION_MS = 60_000;

    private static final class WhoGame {
        final String id;
        final String channelId;
        final MusicRepository.Song song;
        final List<String> optionIds;
        final List<String> optionNames;
        final Set<String> guessed = ConcurrentHashMap.newKeySet();
        boolean over;
        String messageId;
        ScheduledFuture<?> timeout;

        WhoGame(String channelId, MusicRepository.Song song, List<String> optionIds, List<String> optionNames) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.channelId = channelId;
            this.song = song;
            this.optionIds = optionIds;
            this.optionNames = optionNames;
        }
    }

    private static final Map<String, WhoGame> WHO_GAMES = new ConcurrentHashMap<>();

    /** Blocking; call from a worker thread. Returns an error message, or null when the game started. */
    public static String startWhoAdded(MessageChannel channel) {
        if (!BUSY_CHANNELS.add(channel.getId())) return "A music game is already running in this channel.";
        boolean started = false;
        try {
            MusicRepository.Song song = repo().randomMemberSong();
            if (song == null) return "No member-suggested songs in the pool yet.";
            List<String> options = new ArrayList<>(repo().randomCurators(song.addedBy(), 3));
            if (options.size() < 3) return "Who Added It? needs at least 4 different members with songs in the pool.";
            options.add(song.addedBy());
            Collections.shuffle(options);

            JDA jda = channel.getJDA();
            List<String> names = new ArrayList<>();
            for (String id : options) names.add(MusicService.userName(jda, id));

            WhoGame game = new WhoGame(channel.getId(), song, options, names);
            EmbedBuilder eb = new EmbedBuilder()
                    .setColor(GAME_COLOR)
                    .setTitle("Who added this song?")
                    .setDescription("**" + song.displayTitle() + "** by " + song.displayArtist() + "\n\n"
                            + "One of these people put it in the pool. Pick who you think it was.\n"
                            + "-# One guess each. First right answer gets " + WHO_REWARD + " Sparks. Ends <t:"
                            + (System.currentTimeMillis() + WHO_DURATION_MS) / 1000 + ":R>")
                    .setFooter("AMORA Music Arcade", null);
            String art = MusicArt.artworkUrl(song.link());
            if (art != null) eb.setThumbnail(art);

            Message msg = channel.sendMessageEmbeds(eb.build()).setComponents(whoRows(game, null)).complete();
            game.messageId = msg.getId();
            WHO_GAMES.put(game.id, game);
            game.timeout = MusicService.SCHEDULER.schedule(() -> finishWho(channel, game, null), WHO_DURATION_MS, TimeUnit.MILLISECONDS);
            started = true;
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return "Could not start the game right now.";
        } finally {
            if (!started) BUSY_CHANNELS.remove(channel.getId());
        }
    }

    private static List<ActionRow> whoRows(WhoGame game, String revealWinnerId) {
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < game.optionIds.size(); i++) {
            String label = SongMetadata.truncate(game.optionNames.get(i), 78);
            Button b = Button.secondary("music:who:" + game.id + ":" + i, label);
            if (revealWinnerId != null || game.over) {
                b = game.optionIds.get(i).equals(game.song.addedBy())
                        ? Button.success("music:who:" + game.id + ":" + i, label).asDisabled()
                        : b.asDisabled();
            }
            buttons.add(b);
        }
        return List.of(ActionRow.of(buttons));
    }

    public static void handleWhoGuess(ButtonInteractionEvent event, String gameId, int option) {
        WhoGame game = WHO_GAMES.get(gameId);
        if (game == null) {
            event.reply("  This round is over.").setEphemeral(true).queue();
            return;
        }
        String userId = event.getUser().getId();
        synchronized (game) {
            if (game.over) {
                event.reply("  Someone already solved it!").setEphemeral(true).queue();
                return;
            }
            if (userId.equals(game.song.addedBy())) {
                event.reply("You added this one, so you can't guess.").setEphemeral(true).queue();
                return;
            }
            if (!game.guessed.add(userId)) {
                event.reply("  One guess per round!").setEphemeral(true).queue();
                return;
            }
            if (option < 0 || option >= game.optionIds.size() || !game.optionIds.get(option).equals(game.song.addedBy())) {
                event.reply("Nope, not them.").setEphemeral(true).queue();
                return;
            }
            game.over = true;
        }
        if (game.timeout != null) game.timeout.cancel(false);
        event.deferEdit().queue();
        finishWho(event.getChannel(), game, userId);
    }

    private static void finishWho(MessageChannel channel, WhoGame game, String winnerId) {
        synchronized (game) {
            if (winnerId == null && game.over) return; // a winner beat the timeout
            game.over = true;
        }
        WHO_GAMES.remove(game.id);
        BUSY_CHANNELS.remove(game.channelId);
        String result;
        if (winnerId != null) {
            db().addSparks(winnerId, WHO_REWARD);
            repo().addGameWin(winnerId);
            result = "<@" + winnerId + "> got it, it was <@" + game.song.addedBy() + ">. (+" + WHO_REWARD + " Sparks)";
        } else {
            result = "Time's up. It was <@" + game.song.addedBy() + ">.";
        }
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(winnerId != null ? new Color(80, 200, 120) : Color.GRAY)
                .setTitle("Who added this song?")
                .setDescription("**" + game.song.displayTitle() + "** by " + game.song.displayArtist() + "\n\n" + result)
                .setFooter("AMORA Music Arcade", null);
        channel.editMessageEmbedsById(game.messageId, eb.build()).setComponents(whoRows(game, game.song.addedBy()))
                .queue(s -> {}, e -> {});
    }

    // ================================================================ Blurred Cover

    static final int COVER_SIZE = 384;
    static final int[] COVER_BLOCKS = {48, 24, 12, 6};
    static final int[] COVER_REWARDS = {15, 10, 6, 3};
    static final long COVER_STAGE_MS = 15_000;

    private static final class CoverGame {
        final MusicRepository.Song song;
        final BufferedImage art;
        final String channelId;
        String messageId;
        int stage;
        boolean over;
        ScheduledFuture<?> timer;

        CoverGame(MusicRepository.Song song, BufferedImage art, String channelId) {
            this.song = song;
            this.art = art;
            this.channelId = channelId;
        }
    }

    private static final Map<String, CoverGame> COVER_GAMES = new ConcurrentHashMap<>();

    /** Blocking; call from a worker thread. Returns an error message, or null when the game started. */
    public static String startCover(MessageChannel channel) {
        if (!BUSY_CHANNELS.add(channel.getId())) return "A music game is already running in this channel.";
        try {
            MusicRepository.Song song = null;
            BufferedImage art = null;
            for (MusicRepository.Song candidate : repo().randomActiveSongs(6)) {
                art = MusicArt.artwork(candidate.link());
                if (art != null) {
                    song = candidate;
                    break;
                }
            }
            if (song == null) {
                BUSY_CHANNELS.remove(channel.getId());
                return "Could not load any album art right now.";
            }
            CoverGame game = new CoverGame(song, art, channel.getId());
            Message msg = channel.sendMessageEmbeds(coverEmbed(game).build())
                    .addFiles(FileUpload.fromData(MusicArt.pixelatedPng(art, COVER_SIZE, COVER_BLOCKS[0]), "cover.png"))
                    .complete();
            game.messageId = msg.getId();
            COVER_GAMES.put(channel.getId(), game);
            scheduleCoverStage(channel, game);
            return null;
        } catch (Exception e) {
            COVER_GAMES.remove(channel.getId());
            BUSY_CHANNELS.remove(channel.getId());
            e.printStackTrace();
            return "Could not start the game right now.";
        }
    }

    private static EmbedBuilder coverEmbed(CoverGame game) {
        StringBuilder desc = new StringBuilder("Type the song title in chat. The cover gets clearer every "
                + COVER_STAGE_MS / 1000 + " seconds, but the prize goes down.\n\n");
        desc.append("Stage ").append(game.stage + 1).append(" of ").append(COVER_BLOCKS.length)
                .append(", worth ").append(COVER_REWARDS[game.stage]).append(" Sparks\n");
        if (game.stage >= 2) desc.append("Hint: the artist's initials are `").append(SongMetadata.initials(game.song.displayArtist())).append("`\n");
        if (game.stage >= 3) desc.append("Hint: `").append(SongMetadata.titleShape(game.song.displayTitle())).append("`\n");
        return new EmbedBuilder()
                .setColor(GAME_COLOR)
                .setTitle("Guess the cover")
                .setDescription(desc.toString())
                .setImage("attachment://cover.png")
                .setFooter("AMORA Music Arcade", null);
    }

    private static void scheduleCoverStage(MessageChannel channel, CoverGame game) {
        game.timer = MusicService.SCHEDULER.schedule(() -> {
            synchronized (game) {
                if (game.over) return;
                if (game.stage + 1 >= COVER_BLOCKS.length) {
                    endCover(channel, game, null, null);
                    return;
                }
                game.stage++;
            }
            channel.editMessageEmbedsById(game.messageId, coverEmbed(game).build())
                    .setFiles(FileUpload.fromData(MusicArt.pixelatedPng(game.art, COVER_SIZE, COVER_BLOCKS[game.stage]), "cover.png"))
                    .queue(s -> {}, e -> {});
            scheduleCoverStage(channel, game);
        }, game.stage + 1 >= COVER_BLOCKS.length ? COVER_STAGE_MS + 5_000 : COVER_STAGE_MS, TimeUnit.MILLISECONDS);
    }

    /** Checks a chat message against a running cover game. Returns true if it solved the game. */
    public static boolean onChatGuess(MessageReceivedEvent event) {
        CoverGame game = COVER_GAMES.get(event.getChannel().getId());
        if (game == null) return false;
        if (!SongMetadata.isCorrectGuess(event.getMessage().getContentRaw(), game.song.displayTitle())) return false;
        int reward;
        synchronized (game) {
            if (game.over) return false;
            reward = COVER_REWARDS[game.stage];
            endCover(event.getChannel(), game, event.getAuthor().getId(), reward);
        }
        event.getMessage().addReaction(Emoji.fromUnicode("✅")).queue(s -> {}, e -> {});
        return true;
    }

    /** Must be called while holding the game's monitor. */
    private static void endCover(MessageChannel channel, CoverGame game, String winnerId, Integer reward) {
        game.over = true;
        if (game.timer != null) game.timer.cancel(false);
        COVER_GAMES.remove(game.channelId);
        BUSY_CHANNELS.remove(game.channelId);

        String result;
        if (winnerId != null) {
            db().addSparks(winnerId, reward);
            repo().addGameWin(winnerId);
            result = "<@" + winnerId + "> got it on stage " + (game.stage + 1) + ". (+" + reward + " Sparks)";
        } else {
            result = "Nobody got it this time.";
        }
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(winnerId != null ? new Color(80, 200, 120) : Color.GRAY)
                .setTitle("Guess the cover")
                .setDescription("It was **" + game.song.displayTitle() + "** by " + game.song.displayArtist() + "\n"
                        + game.song.link() + "\n\n" + result)
                .setImage("attachment://cover.png")
                .setFooter("AMORA Music Arcade", null);
        channel.editMessageEmbedsById(game.messageId, eb.build())
                .setFiles(FileUpload.fromData(MusicArt.pixelatedPng(game.art, COVER_SIZE, 1), "cover.png"))
                .queue(s -> {}, e -> {});
    }

    // ================================================================ Songdle (daily, private)

    static final int SONGDLE_MAX = 6;
    static final int[] SONGDLE_BLOCKS = {40, 24, 14, 8, 4, 2};
    private static volatile String songdleCacheDate;
    private static volatile MusicRepository.Song songdleCacheSong;
    private static volatile BufferedImage songdleCacheArt;

    private record SongdleDay(String date, MusicRepository.Song song, BufferedImage art) {
    }

    /** Today's puzzle song, chosen once per UTC day and persisted in bot_state. */
    private static synchronized SongdleDay songdleDay() {
        String date = LocalDate.now(java.time.ZoneOffset.UTC).toString();
        if (date.equals(songdleCacheDate) && songdleCacheSong != null) {
            return new SongdleDay(date, songdleCacheSong, songdleCacheArt);
        }
        String stateKey = "songdle_song_" + date;
        MusicRepository.Song song = null;
        BufferedImage art = null;
        String stored = db().getBotState(stateKey);
        if (stored != null) {
            song = repo().songById(Integer.parseInt(stored));
            if (song != null) art = MusicArt.artwork(song.link());
        }
        if (song == null) {
            MusicRepository.Feature todayFeature = repo().feature(date);
            for (MusicRepository.Song candidate : repo().randomActiveSongs(10)) {
                if (todayFeature != null && candidate.songId() == todayFeature.songId()) continue;
                BufferedImage img = MusicArt.artwork(candidate.link());
                if (img != null) {
                    song = candidate;
                    art = img;
                    break;
                }
            }
            if (song == null) return null;
            db().setBotState(stateKey, String.valueOf(song.songId()));
        }
        if (art == null) return null;
        songdleCacheDate = date;
        songdleCacheSong = song;
        songdleCacheArt = art;
        return new SongdleDay(date, song, art);
    }

    public static void startSongdle(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> MusicService.WORKERS.execute(() -> {
            SongdleDay day = songdleDay();
            if (day == null) {
                hook.editOriginal("  Songdle needs songs with album art in the pool. Try again later!").queue();
                return;
            }
            MusicRepository.SongdlePlay play = repo().songdlePlay(day.date(), event.getUser().getId());
            renderSongdle(hook, day, play == null ? new MusicRepository.SongdlePlay("", false, false) : play, null);
        }));
    }

    private static void renderSongdle(InteractionHook hook, SongdleDay day, MusicRepository.SongdlePlay play, String note) {
        int used = play.history().length();
        MusicRepository.Song s = day.song();
        StringBuilder desc = new StringBuilder();
        if (note != null) desc.append(note).append("\n\n");

        if (play.finished()) {
            desc.append(play.solved() ? "You got it in " + used + "/" + SONGDLE_MAX + ".\n" : "Out of guesses.\n");
            desc.append("It was **").append(s.displayTitle()).append("** by ").append(s.displayArtist()).append("\n")
                    .append(s.link()).append("\n\n").append(songdleGrid(play.history()))
                    .append("\n-# New one tomorrow (resets at 00:00 UTC).");
        } else {
            desc.append("Guess today's song from the cover. Each guess or skip gives you another hint.\n\n");
            desc.append("Guesses: ").append(used).append("/").append(SONGDLE_MAX).append("  ")
                    .append(songdleGrid(play.history())).append("\n\n");
            if (used >= 1) desc.append("Title: `").append(SongMetadata.titleShape(s.displayTitle())).append("`\n");
            if (used >= 2) desc.append("Artist initials: `").append(SongMetadata.initials(s.displayArtist())).append("`\n");
            if (used >= 3) desc.append("Added by: ").append(s.creditLine()).append(" (").append(s.source()).append(")\n");
            if (used >= 4) desc.append("Artist: **").append(s.displayArtist()).append("**\n");
            if (used >= 5) desc.append("Title starts with: `").append(firstLetters(s.displayTitle())).append("`\n");
        }
        int block = play.finished() ? 1 : SONGDLE_BLOCKS[Math.min(used, SONGDLE_BLOCKS.length - 1)];
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(play.finished() && play.solved() ? new Color(80, 200, 120) : GAME_COLOR)
                .setTitle("Songdle, " + day.date())
                .setDescription(desc.toString())
                .setImage("attachment://songdle.png")
                .setFooter("Solve it in fewer guesses for more Sparks", null);

        ActionRow row = play.finished()
                ? ActionRow.of(Button.primary("music:sd:share", "Share result"))
                : ActionRow.of(
                        Button.success("music:sd:guess", "Guess"),
                        Button.secondary("music:sd:skip", "Skip"));
        hook.editOriginalEmbeds(eb.build())
                .setContent("")
                .setFiles(FileUpload.fromData(MusicArt.pixelatedPng(day.art(), 320, block), "songdle.png"))
                .setComponents(row)
                .queue(s2 -> {}, e -> {});
    }

    private static String firstLetters(String title) {
        StringBuilder sb = new StringBuilder();
        for (String word : title.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            sb.appendCodePoint(word.codePointAt(0));
            for (int i = 1; i < word.codePointCount(0, word.length()); i++) sb.append('▢');
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    static String songdleGrid(String history) {
        StringBuilder sb = new StringBuilder();
        for (char c : history.toCharArray()) sb.append(c == 'o' ? "🟩" : c == 's' ? "⬛" : "🟥");
        for (int i = history.length(); i < SONGDLE_MAX; i++) sb.append("▫️");
        return sb.toString();
    }

    public static void handleSongdleButton(ButtonInteractionEvent event, String action) {
        if (action.equals("guess")) {
            TextInput input = TextInput.create("title", "Song title", TextInputStyle.SHORT)
                    .setPlaceholder("e.g. Shut Down").setRequired(true).setMaxLength(120).build();
            event.replyModal(Modal.create("music:sdm", "Guess today's Songdle").addActionRow(input).build()).queue();
            return;
        }
        if (action.equals("skip")) {
            event.deferEdit().queue();
            MusicService.WORKERS.execute(() -> applySongdleGuess(event.getHook(), event.getUser().getId(), 's', "Skipped. Here's another hint."));
            return;
        }
        if (action.equals("share")) {
            event.deferEdit().queue();
            MusicService.WORKERS.execute(() -> {
                SongdleDay day = songdleDay();
                if (day == null) return;
                MusicRepository.SongdlePlay play = repo().songdlePlay(day.date(), event.getUser().getId());
                if (play == null || !play.finished()) return;
                String score = play.solved() ? play.history().length() + "/" + SONGDLE_MAX : "X/" + SONGDLE_MAX;
                event.getChannel().sendMessage(event.getUser().getAsMention() + " played Songdle " + day.date()
                        + ": " + score + "\n" + songdleGrid(play.history()) + "\n-# /music songdle").queue(s -> {}, e -> {});
            });
        }
    }

    public static void handleSongdleModal(ModalInteractionEvent event) {
        String guess = event.getValue("title") == null ? "" : event.getValue("title").getAsString();
        event.deferEdit().queue();
        MusicService.WORKERS.execute(() -> {
            SongdleDay day = songdleDay();
            if (day == null) return;
            boolean correct = SongMetadata.isCorrectGuess(guess, day.song().displayTitle());
            applySongdleGuess(event.getHook(), event.getUser().getId(), correct ? 'o' : 'x',
                    correct ? null : "\"" + SongMetadata.truncate(guess, 60) + "\" isn't it.");
        });
    }

    private static void applySongdleGuess(InteractionHook hook, String userId, char result, String note) {
        SongdleDay day = songdleDay();
        if (day == null) return;
        MusicRepository.SongdlePlay play = repo().appendSongdleGuess(day.date(), userId, result, SONGDLE_MAX);
        if (play == null) {
            MusicRepository.SongdlePlay existing = repo().songdlePlay(day.date(), userId);
            if (existing != null) renderSongdle(hook, day, existing, "  You already finished today's Songdle.");
            return;
        }
        if (play.solved() && result == 'o') {
            int reward = (SONGDLE_MAX + 1 - play.history().length()) * 3;
            db().addSparks(userId, reward);
            repo().addGameWin(userId);
            note = "That's it! +" + reward + " Sparks.";
        }
        renderSongdle(hook, day, play, note);
    }

    // ================================================================ Higher or Lower (YouTube views)

    static final int HL_RUNS_PER_DAY = 3;
    static final int HL_SPARKS_PER_POINT = 2;
    static final int HL_MAX_REWARD = 30;
    static final long HL_VIEWS_TTL_MS = TimeUnit.DAYS.toMillis(7);

    private static final class HlSession {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final String userId;
        final boolean rewarded;
        final Deque<MusicRepository.Song> pool = new ArrayDeque<>();
        MusicRepository.Song a;
        MusicRepository.Song b;
        int streak;
        long lastActive = System.currentTimeMillis();

        HlSession(String userId, boolean rewarded) {
            this.userId = userId;
            this.rewarded = rewarded;
        }
    }

    private static final Map<String, HlSession> HL_SESSIONS = new ConcurrentHashMap<>();

    /** Loads random YouTube songs and makes sure their view counts are fresh. */
    private static List<MusicRepository.Song> songsWithViews() throws Exception {
        List<MusicRepository.Song> songs = repo().randomYouTubeSongs(40);
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (MusicRepository.Song s : songs) {
            if (s.viewCount() < 0 || now - s.viewCountAt() > HL_VIEWS_TTL_MS) {
                String vid = SongMetadata.youTubeVideoId(s.link());
                if (vid != null) stale.add(vid);
            }
        }
        Map<String, Long> fresh = stale.isEmpty() ? Map.of() : YouTubePlaylistImporter.fetchViewCounts(stale);
        List<MusicRepository.Song> out = new ArrayList<>();
        for (MusicRepository.Song s : songs) {
            String vid = SongMetadata.youTubeVideoId(s.link());
            Long views = vid == null ? null : fresh.get(vid);
            if (views != null) {
                repo().setViewCount(s.songId(), views, now);
                out.add(withViews(s, views, now));
            } else if (s.viewCount() >= 0 && now - s.viewCountAt() <= HL_VIEWS_TTL_MS) {
                out.add(s);
            }
        }
        return out;
    }

    private static MusicRepository.Song withViews(MusicRepository.Song s, long views, long at) {
        return new MusicRepository.Song(s.songId(), s.addedBy(), s.title(), s.artist(), s.link(), s.source(), s.active(),
                s.createdAt(), s.lastFeaturedAt(), s.displayTitle(), s.displayArtist(), s.tags(), s.origin(), views, at);
    }

    private static MusicRepository.Song nextFromPool(HlSession session) throws Exception {
        if (session.pool.isEmpty()) {
            for (MusicRepository.Song s : songsWithViews()) {
                if ((session.a == null || s.songId() != session.a.songId()) && (session.b == null || s.songId() != session.b.songId())) {
                    session.pool.add(s);
                }
            }
        }
        return session.pool.poll();
    }

    public static void startHigherLower(SlashCommandInteractionEvent event) {
        if (!YouTubePlaylistImporter.isConfigured()) {
            event.reply("  Higher or Lower needs `YOUTUBE_API_KEY` to be configured.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> MusicService.WORKERS.execute(() -> {
            String userId = event.getUser().getId();
            HL_SESSIONS.values().removeIf(s -> s.userId.equals(userId)
                    || System.currentTimeMillis() - s.lastActive > TimeUnit.MINUTES.toMillis(15));

            String today = LocalDate.now(java.time.ZoneOffset.UTC).toString();
            MusicRepository.Stats stats = repo().stats(userId);
            int runs = today.equals(stats.hlRunsDate()) ? stats.hlRuns() : 0;
            boolean rewarded = runs < HL_RUNS_PER_DAY;
            repo().saveHigherLower(userId, stats.hlBest(), today, runs + 1);

            HlSession session = new HlSession(userId, rewarded);
            try {
                session.a = nextFromPool(session);
                session.b = nextFromPool(session);
            } catch (Exception e) {
                hook.editOriginal("  Could not load view counts: " + e.getMessage()).queue();
                return;
            }
            if (session.a == null || session.b == null) {
                hook.editOriginal("  Higher or Lower needs at least 2 YouTube songs in the pool.").queue();
                return;
            }
            HL_SESSIONS.put(session.id, session);
            String note = rewarded ? "You get " + HL_SPARKS_PER_POINT + " Sparks per correct answer (up to " + HL_MAX_REWARD + ")."
                    : "Just for fun this time, you've used your " + HL_RUNS_PER_DAY + " paid runs for today.";
            hook.editOriginalEmbeds(hlEmbed(session, note).build()).setComponents(hlRow(session, false)).queue();
        }));
    }

    private static String views(long v) {
        return NumberFormat.getIntegerInstance(Locale.US).format(v);
    }

    private static EmbedBuilder hlEmbed(HlSession s, String note) {
        String vid = SongMetadata.youTubeVideoId(s.b.link());
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(GAME_COLOR)
                .setTitle("Higher or lower?")
                .setDescription((note == null ? "" : note + "\n\n")
                        + "**A:** " + SongMetadata.truncate(s.a.displayTitle(), 60) + " - " + SongMetadata.truncate(s.a.displayArtist(), 30)
                        + "\n" + views(s.a.viewCount()) + " views on YouTube\n\n"
                        + "**B:** " + SongMetadata.truncate(s.b.displayTitle(), 60) + " - " + SongMetadata.truncate(s.b.displayArtist(), 30)
                        + "\n? views\n\nDoes B have more or fewer views than A?")
                .addField("Streak", "`" + s.streak + "`", true)
                .setFooter("AMORA Music Arcade", null);
        if (vid != null) eb.setThumbnail("https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg");
        return eb;
    }

    private static ActionRow hlRow(HlSession s, boolean disabled) {
        return ActionRow.of(
                Button.success("music:hl:" + s.id + ":hi", "Higher").withDisabled(disabled),
                Button.danger("music:hl:" + s.id + ":lo", "Lower").withDisabled(disabled));
    }

    public static void handleHigherLower(ButtonInteractionEvent event, String sessionId, boolean higher) {
        HlSession session = HL_SESSIONS.get(sessionId);
        if (session == null || !session.userId.equals(event.getUser().getId())) {
            event.reply("  This run has ended. Start a new one with `/music game`.").setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue();
        MusicService.WORKERS.execute(() -> {
            synchronized (session) {
                session.lastActive = System.currentTimeMillis();
                long a = session.a.viewCount();
                long b = session.b.viewCount();
                boolean correct = b == a || (higher == (b > a));
                String reveal = "**" + SongMetadata.truncate(session.b.displayTitle(), 50) + "** has " + views(b) + " views.";
                if (correct) {
                    session.streak++;
                    session.a = session.b;
                    try {
                        session.b = nextFromPool(session);
                    } catch (Exception e) {
                        session.b = null;
                    }
                    if (session.b != null) {
                        event.getHook().editOriginalEmbeds(hlEmbed(session, "Correct. " + reveal).build())
                                .setComponents(hlRow(session, false)).queue();
                        return;
                    }
                    endHigherLower(event.getHook(), session, "You went through every song I had. " + reveal);
                    return;
                }
                endHigherLower(event.getHook(), session, "Wrong. " + reveal);
            }
        });
    }

    private static void endHigherLower(InteractionHook hook, HlSession session, String reveal) {
        HL_SESSIONS.remove(session.id);
        int reward = session.rewarded ? Math.min(HL_MAX_REWARD, session.streak * HL_SPARKS_PER_POINT) : 0;
        if (reward > 0) db().addSparks(session.userId, reward);
        MusicRepository.Stats stats = repo().stats(session.userId);
        boolean record = session.streak > stats.hlBest();
        if (record) repo().saveHigherLower(session.userId, session.streak, stats.hlRunsDate(), stats.hlRuns());
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(session.streak > 0 ? new Color(80, 200, 120) : Color.GRAY)
                .setTitle("Higher or lower? Game over")
                .setDescription(reveal + "\n\nYou got " + session.streak + " in a row"
                        + (record ? ", your best so far." : " (your best is " + stats.hlBest() + ").")
                        + (reward > 0 ? "\n+" + reward + " Sparks" : ""))
                .setFooter("AMORA Music Arcade", null);
        hook.editOriginalEmbeds(eb.build()).setComponents(hlRow(session, true)).queue();
    }
}
