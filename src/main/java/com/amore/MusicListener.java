package com.amore;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.FileUpload;

/** Routes /song, /music, music buttons/modals, chat guesses and Song of the Day thread replies. */
public class MusicListener extends ListenerAdapter {

    static final String MUSIC_ADMIN_ROLE_IDS = System.getenv("MUSIC_ADMIN_ROLE_IDS");
    static final int MEMBER_ADDS_PER_DAY = 5;

    private static MusicRepository repo() {
        return MusicRepository.get();
    }

    // ================================================================ registration

    public static List<CommandData> commands() {
        String tagHelp = "Up to 3 of: " + String.join(", ", SongMetadata.TAG_VOCABULARY);
        return List.of(
                Commands.slash("song", "Manage the AMORA daily song pool").addSubcommands(
                        new SubcommandData("add", "Add a Spotify or YouTube song to the daily pool")
                                .addOption(OptionType.STRING, "title", "Song title", true)
                                .addOption(OptionType.STRING, "artist", "Artist name", true)
                                .addOption(OptionType.STRING, "link", "Spotify or YouTube link", true)
                                .addOption(OptionType.STRING, "tags", SongMetadata.truncate(tagHelp, 100), false),
                        new SubcommandData("importplaylist", "Staff: import all songs from a public YouTube playlist")
                                .addOption(OptionType.STRING, "link", "Public YouTube playlist URL", true),
                        new SubcommandData("remove", "Remove a song by ID (owner or staff)")
                                .addOption(OptionType.INTEGER, "id", "Song ID", true),
                        new SubcommandData("tag", "Set the tags of a song (owner or staff)")
                                .addOption(OptionType.INTEGER, "id", "Song ID", true)
                                .addOption(OptionType.STRING, "tags", SongMetadata.truncate(tagHelp, 100), true),
                        new SubcommandData("list", "Staff: download the full song pool directory"),
                        new SubcommandData("suggest", "Get a random song recommendation from the pool"),
                        new SubcommandData("postnow", "Staff: post a bonus song drop now")),
                Commands.slash("music", "AMORA music games, collections and stats").addSubcommands(
                        new SubcommandData("help", "How Song of the Day, music games and vinyls work"),
                        new SubcommandData("songdle", "Play today's Songdle: guess the song from its cover"),
                        new SubcommandData("game", "Start a music minigame")
                                .addOptions(new OptionData(OptionType.STRING, "type", "Which game", true)
                                        .addChoice("Who Added It?", "whoadded")
                                        .addChoice("Blurred Cover", "cover")
                                        .addChoice("Higher or Lower", "higherlower")),
                        new SubcommandData("vinyls", "View a vinyl collection")
                                .addOption(OptionType.USER, "user", "Whose shelf to view", false),
                        new SubcommandData("crate", "Open a vinyl crate (" + MusicService.CRATE_COST + " Sparks)"),
                        new SubcommandData("sponsor", "Sponsor a song as an upcoming Song of the Day (" + MusicService.SPONSOR_COST + " Sparks)")
                                .addOption(OptionType.INTEGER, "id", "Song ID", true),
                        new SubcommandData("stats", "View music stats")
                                .addOption(OptionType.USER, "user", "Whose stats to view", false),
                        new SubcommandData("match", "Compare music taste, or find your music twin")
                                .addOption(OptionType.USER, "user", "Compare with this member (leave empty to find your twin)", false),
                        new SubcommandData("battle", "Show the weekly Song Battle (staff can start one)")
                                .addOption(OptionType.BOOLEAN, "start", "Staff: start a new battle now", false),
                        new SubcommandData("recap", "Staff: post a monthly music recap card")
                                .addOption(OptionType.STRING, "month", "Month as YYYY-MM (default: this month so far)", false)));
    }

    // ================================================================ slash commands

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();
        if (!name.equals("song") && !name.equals("music")) return;
        String sub = event.getSubcommandName();
        if (sub == null) {
            event.reply("  Missing subcommand.").setEphemeral(true).queue();
            return;
        }
        if (name.equals("song")) {
            handleSong(event, sub);
        } else {
            handleMusic(event, sub);
        }
    }

    private boolean isStaff(SlashCommandInteractionEvent event) {
        return MusicService.isStaff(event.getMember(), MUSIC_ADMIN_ROLE_IDS);
    }

    /** Defers ephemerally and runs the work off the gateway thread. */
    private static void deferred(SlashCommandInteractionEvent event, boolean ephemeral, java.util.function.Consumer<InteractionHook> work) {
        event.deferReply(ephemeral).queue(hook -> MusicService.WORKERS.execute(() -> {
            try {
                work.accept(hook);
            } catch (Exception e) {
                e.printStackTrace();
                hook.editOriginal("  Something went wrong: " + e.getMessage()).queue(s -> {}, err -> {});
            }
        }));
    }

    static EmbedBuilder songEmbed(MusicRepository.Song song, String title, String footer) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(MusicService.PINK)
                .setTitle(title)
                .setDescription("**" + song.displayTitle() + "**\nby " + song.displayArtist() + "\n\n"
                        + song.link() + "\n\nSuggested by " + song.creditLine())
                .addField("Source", song.source(), true)
                .addField("Song ID", "#" + song.songId(), true)
                .setFooter(footer, null);
        List<String> tags = SongMetadata.tagList(song.tags());
        if (!tags.isEmpty()) eb.addField("Tags", "`" + String.join("` `", tags) + "`", true);
        String art = MusicArt.artworkUrl(song.link());
        if (art != null) eb.setImage(art);
        return eb;
    }

    private void handleSong(SlashCommandInteractionEvent event, String sub) {
        String userId = event.getUser().getId();
        switch (sub) {
            case "add" -> {
                String title = event.getOption("title").getAsString().trim();
                String artist = event.getOption("artist").getAsString().trim();
                String link = SongMetadata.normalizeLink(event.getOption("link").getAsString().trim());
                OptionMapping tagsOpt = event.getOption("tags");
                String tags = SongMetadata.parseTags(tagsOpt == null ? null : tagsOpt.getAsString());

                if (title.isBlank() || artist.isBlank() || link.isBlank()) {
                    event.reply("  Title, artist, and link are required.").setEphemeral(true).queue();
                    return;
                }
                if (title.length() > 120 || artist.length() > 120 || link.length() > 500) {
                    event.reply("  One or more fields are too long.").setEphemeral(true).queue();
                    return;
                }
                if (!SongMetadata.isSupportedLink(link)) {
                    event.reply("  Please submit a valid Spotify track or YouTube song link.").setEphemeral(true).queue();
                    return;
                }
                if (tags == null) {
                    event.reply("  Unknown tag. Use up to 3 of: `" + String.join("` `", SongMetadata.TAG_VOCABULARY) + "`")
                            .setEphemeral(true).queue();
                    return;
                }
                boolean staff = isStaff(event);
                deferred(event, true, hook -> {
                    if (!staff) {
                        long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
                        if (repo().countMemberAddsSince(userId, since) >= MEMBER_ADDS_PER_DAY) {
                            hook.editOriginal("  You can add up to **" + MEMBER_ADDS_PER_DAY + "** songs per 24 hours. Try again later!").queue();
                            return;
                        }
                    }
                    if (repo().activeSongByLink(link) != null) {
                        hook.editOriginal("  That exact song link is already in the AMORA pool.").queue();
                        return;
                    }
                    SongMetadata.DisplayMeta meta = SongMetadata.fromMember(title, artist);
                    MusicRepository.Song dupe = repo().activeSongByDedupeKey(SongMetadata.dedupeKey(meta.title(), meta.artist()));
                    if (dupe != null) {
                        hook.editOriginal("  **" + dupe.displayTitle() + "** by **" + dupe.displayArtist()
                                + "** is already in the pool (song #" + dupe.songId() + ", " + dupe.source() + ").").queue();
                        return;
                    }
                    MusicRepository.Song created = repo().addMemberSong(userId, title, artist, link, SongMetadata.detectSource(link), tags);
                    if (created == null) {
                        hook.editOriginal("  Failed to save the song suggestion.").queue();
                        return;
                    }
                    hook.editOriginalEmbeds(songEmbed(created, "Song added", "Thanks for adding to the pool!").build()).queue();
                });
            }
            case "importplaylist" -> {
                if (!isStaff(event)) {
                    event.reply("  Only AMORA Staff can mass-import playlists.").setEphemeral(true).queue();
                    return;
                }
                String playlistLink = event.getOption("link").getAsString().trim();
                if (!SongMetadata.isYouTubePlaylistLink(playlistLink)) {
                    event.reply("  Please provide a valid public YouTube playlist link or Playlist ID.").setEphemeral(true).queue();
                    return;
                }
                deferred(event, true, hook -> importPlaylist(hook, userId, playlistLink));
            }
            case "remove" -> {
                int songId = event.getOption("id").getAsInt();
                boolean staff = isStaff(event);
                deferred(event, true, hook -> {
                    MusicRepository.Song song = repo().songById(songId);
                    if (song == null || !song.active()) {
                        hook.editOriginal("  That song ID does not exist in the active pool.").queue();
                        return;
                    }
                    boolean owner = song.isMemberPick() && userId.equals(song.addedBy());
                    if (!owner && !staff) {
                        hook.editOriginal("  You can only remove songs you added yourself unless you are AMORA Staff.").queue();
                        return;
                    }
                    if (!repo().deactivate(songId)) {
                        hook.editOriginal("  Failed to remove that song.").queue();
                        return;
                    }
                    MusicService.refundSponsors(songId);
                    hook.editOriginal(" Removed **" + song.displayTitle() + "** by **" + song.displayArtist() + "** from the AMORA pool.").queue();
                });
            }
            case "tag" -> {
                int songId = event.getOption("id").getAsInt();
                String tags = SongMetadata.parseTags(event.getOption("tags").getAsString());
                if (tags == null) {
                    event.reply("  Unknown tag. Use up to 3 of: `" + String.join("` `", SongMetadata.TAG_VOCABULARY) + "`")
                            .setEphemeral(true).queue();
                    return;
                }
                boolean staff = isStaff(event);
                deferred(event, true, hook -> {
                    MusicRepository.Song song = repo().songById(songId);
                    if (song == null || !song.active()) {
                        hook.editOriginal("  That song ID does not exist in the active pool.").queue();
                        return;
                    }
                    if (!staff && !(song.isMemberPick() && userId.equals(song.addedBy()))) {
                        hook.editOriginal("  You can only tag songs you added yourself unless you are AMORA Staff.").queue();
                        return;
                    }
                    repo().setTags(songId, tags);
                    List<String> list = SongMetadata.tagList(tags);
                    hook.editOriginal("Tags for **" + song.displayTitle() + "**: "
                            + (list.isEmpty() ? "_none_" : "`" + String.join("` `", list) + "`")).queue();
                });
            }
            case "list" -> {
                if (!isStaff(event)) {
                    event.reply("  Only AMORA Staff can view the full song pool directory.").setEphemeral(true).queue();
                    return;
                }
                deferred(event, true, hook -> {
                    List<MusicRepository.Song> songs = repo().allActiveSongs();
                    if (songs.isEmpty()) {
                        hook.editOriginal("  The AMORA song pool is empty right now. Add one with `/song add`.").queue();
                        return;
                    }
                    StringBuilder file = new StringBuilder("AMORA song pool\nActive songs: ")
                            .append(songs.size()).append("\n=========================================\n\n");
                    for (MusicRepository.Song s : songs) {
                        file.append("[ ID: #").append(s.songId()).append(" ]\n")
                                .append("Title   : ").append(s.displayTitle()).append("\n")
                                .append("Artist  : ").append(s.displayArtist()).append("\n")
                                .append("Link    : ").append(s.link()).append("\n")
                                .append("Origin  : ").append(s.isMemberPick() ? "member" : "playlist import").append("\n")
                                .append("Added By: ").append(s.addedBy()).append("\n");
                        if (!s.tags().isBlank()) file.append("Tags    : ").append(String.join(", ", SongMetadata.tagList(s.tags()))).append("\n");
                        file.append("-----------------------------------------\n");
                    }
                    hook.editOriginal("Here's the full song pool (" + songs.size() + " songs). Use Ctrl+F to find a song ID.")
                            .setFiles(FileUpload.fromData(file.toString().getBytes(StandardCharsets.UTF_8), "AMORA_Song_Directory.txt"))
                            .queue();
                });
            }
            case "suggest" -> deferred(event, false, hook -> {
                MusicRepository.Song song = repo().randomActiveSong();
                if (song == null) {
                    hook.editOriginal("  There are no active song suggestions yet.").queue();
                    return;
                }
                hook.editOriginalEmbeds(songEmbed(song, "Random pick from the pool", "AMORA Music").build()).queue();
            });
            case "postnow" -> {
                if (!isStaff(event)) {
                    event.reply("  Only AMORA Staff can force-post a song.").setEphemeral(true).queue();
                    return;
                }
                if (MusicService.DAILY_CHANNEL_ID == null) {
                    event.reply("  DAILY_SONG_CHANNEL_ID is not configured.").setEphemeral(true).queue();
                    return;
                }
                deferred(event, true, hook -> {
                    boolean posted = MusicService.postDaily(event.getJDA(), false);
                    hook.editOriginal(posted
                            ? " Bonus song drop posted in <#" + MusicService.DAILY_CHANNEL_ID + ">."
                            : "  Could not post a song right now. Check the channel ID or make sure the pool has songs.").queue();
                });
            }
            default -> event.reply("  Unknown song subcommand.").setEphemeral(true).queue();
        }
    }

    private static void importPlaylist(InteractionHook hook, String userId, String playlistLink) {
        List<YouTubePlaylistImporter.ImportedSong> imported;
        try {
            imported = YouTubePlaylistImporter.importPlaylist(playlistLink);
        } catch (Exception e) {
            hook.editOriginal("  Failed to import playlist: " + e.getMessage()).queue();
            return;
        }
        if (imported.isEmpty()) {
            hook.editOriginal("  No usable songs were found in that playlist.").queue();
            return;
        }
        int skippedExisting = 0;
        int skippedInvalid = 0;
        Set<String> seenLinks = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        List<YouTubePlaylistImporter.ImportedSong> toAdd = new ArrayList<>();
        StringBuilder preview = new StringBuilder();
        for (YouTubePlaylistImporter.ImportedSong item : imported) {
            String link = SongMetadata.normalizeLink(item.link());
            if (link.isBlank() || !SongMetadata.isSupportedLink(link) || !seenLinks.add(link.toLowerCase(Locale.ROOT))) {
                skippedInvalid++;
                continue;
            }
            SongMetadata.DisplayMeta meta = SongMetadata.fromYouTube(item.title(), item.artist());
            String key = SongMetadata.dedupeKey(meta.title(), meta.artist());
            if (repo().activeSongByLink(link) != null || (!key.isEmpty() && (!seenKeys.add(key) || repo().activeSongByDedupeKey(key) != null))) {
                skippedExisting++;
                continue;
            }
            toAdd.add(new YouTubePlaylistImporter.ImportedSong(item.title(), item.artist(), link));
            if (preview.length() < 900) {
                preview.append("**").append(SongMetadata.truncate(meta.title(), 40)).append("** - ")
                        .append(SongMetadata.truncate(meta.artist(), 30)).append("\n");
            }
        }
        int added = repo().addImportedSongs(userId, toAdd);
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(MusicService.PINK)
                .setTitle("Playlist imported")
                .setDescription("Added: " + added + "\nAlready in the pool: " + skippedExisting
                        + "\nSkipped: " + skippedInvalid + "\n\n-# Imported songs are credited to the staff playlist, not to you.")
                .setFooter("AMORA Music Importer", null);
        if (preview.length() > 0) eb.addField("Imported Songs Preview", preview.toString(), false);
        hook.editOriginalEmbeds(eb.build()).setContent("").queue();
    }

    static EmbedBuilder helpEmbed(boolean staff) {
        String daily = MusicService.DAILY_CHANNEL_ID == null ? "the song channel" : "<#" + MusicService.DAILY_CHANNEL_ID + ">";
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(MusicService.PINK)
                .setTitle("Music commands")
                .setDescription("Every day the bot posts a song in " + daily + ". Vote on it, listen, and talk about it in the thread. "
                        + "You earn Sparks for joining in.")
                .addField("Song of the Day",
                        "Vote with 🔥, 🙂 or 💤. You can change your vote until the next song.\n"
                                + "Hit **Listened** once a day for 1 Spark. It keeps your streak going (10 bonus Sparks every 7 days) "
                                + "and adds the song to your vinyls.\n"
                                + "The first " + MusicService.THREAD_REWARD_SLOTS + " people to post a real opinion in the thread get "
                                + MusicService.THREAD_REWARD_SPARKS + " Sparks each.\n"
                                + "If a song you added gets picked, you earn Sparks and Curator Points based on the votes.", false)
                .addField("Adding songs",
                        "`/song add`: add a Spotify or YouTube song (" + MEMBER_ADDS_PER_DAY + " a day)\n"
                                + "`/song tag`: tag your song (" + String.join(", ", SongMetadata.TAG_VOCABULARY) + ")\n"
                                + "`/song remove`: remove a song you added\n"
                                + "`/song suggest`: get a random song from the pool\n"
                                + "-# Some days have themes: chill on Monday, underrated on Wednesday, throwback on Thursday, "
                                + "hype on Friday, acoustic on Sunday.", false)
                .addField("Games",
                        "`/music songdle`: guess the day's song from its cover, 6 tries\n"
                                + "`/music game`, then pick one:\n"
                                + "- **Who Added It?**: guess which member added a song\n"
                                + "- **Blurred Cover**: type the title in chat while the cover gets clearer\n"
                                + "- **Higher or Lower**: guess which song has more YouTube views\n"
                                + "-# Who Added It? and Blurred Cover sometimes show up in the lounge on their own too.", false)
                .addField("Vinyls",
                        "`/music vinyls`: see your collection. Rarity depends on how people rated each song.\n"
                                + "`/music crate`: get a random vinyl for " + MusicService.CRATE_COST + " Sparks\n"
                                + "`/music sponsor`: pay " + MusicService.SPONSOR_COST + " Sparks to get a song picked as an upcoming Song of the Day", false)
                .addField("Stats and events",
                        "`/music stats`: your music stats\n"
                                + "`/music match`: see how similar your taste is to someone else's\n"
                                + "`/music battle`: the weekly Song Battle (starts every Monday)\n"
                                + "`/leaderboard`: pick Top Music Curators", false)
                .setFooter("Song IDs are on every song post", null);
        if (staff) {
            eb.addField("Staff",
                    "`/song postnow`, `/song importplaylist`, `/song list`, `/music battle start:True`, `/music recap`", false);
        }
        return eb;
    }

    private void handleMusic(SlashCommandInteractionEvent event, String sub) {
        String userId = event.getUser().getId();
        switch (sub) {
            case "help" -> event.replyEmbeds(helpEmbed(isStaff(event)).build()).setEphemeral(true).queue();
            case "songdle" -> MusicGames.startSongdle(event);
            case "game" -> {
                String type = event.getOption("type").getAsString();
                if (type.equals("higherlower")) {
                    MusicGames.startHigherLower(event);
                    return;
                }
                deferred(event, true, hook -> {
                    String err = type.equals("whoadded") ? MusicGames.startWhoAdded(event.getChannel()) : MusicGames.startCover(event.getChannel());
                    hook.editOriginal(err == null ? "Started! It's in this channel." : "  " + err).queue();
                });
            }
            case "vinyls" -> {
                User target = event.getOption("user") == null ? event.getUser() : event.getOption("user").getAsUser();
                deferred(event, false, hook -> hook.editOriginalEmbeds(MusicService.vinylCollection(target).build()).queue());
            }
            case "crate" -> deferred(event, false, hook -> hook.editOriginal(MusicService.openCrate(userId)).queue());
            case "sponsor" -> {
                int songId = event.getOption("id").getAsInt();
                deferred(event, true, hook -> hook.editOriginal(MusicService.sponsor(userId, songId)).queue());
            }
            case "stats" -> {
                User target = event.getOption("user") == null ? event.getUser() : event.getOption("user").getAsUser();
                deferred(event, false, hook -> hook.editOriginalEmbeds(MusicService.statsEmbed(target).build()).queue());
            }
            case "match" -> {
                User other = event.getOption("user") == null ? null : event.getOption("user").getAsUser();
                if (other != null && other.getId().equals(userId)) {
                    event.reply("Pick someone other than yourself.").setEphemeral(true).queue();
                    return;
                }
                deferred(event, false, hook -> hook.editOriginal(MusicService.twin(userId, other == null ? null : other.getId())).queue());
            }
            case "battle" -> {
                boolean start = event.getOption("start") != null && event.getOption("start").getAsBoolean();
                if (start && !isStaff(event)) {
                    event.reply("  Only AMORA Staff can start a song battle.").setEphemeral(true).queue();
                    return;
                }
                deferred(event, true, hook -> {
                    if (start) {
                        String err = MusicBattle.start(event.getJDA());
                        hook.editOriginal(err == null ? "Battle started. " + MusicBattle.status() : "  " + err).queue();
                    } else {
                        hook.editOriginal(MusicBattle.status()).queue();
                    }
                });
            }
            case "recap" -> {
                if (!isStaff(event)) {
                    event.reply("  Only AMORA Staff can post a recap.").setEphemeral(true).queue();
                    return;
                }
                OptionMapping monthOpt = event.getOption("month");
                YearMonth month;
                try {
                    month = monthOpt == null ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(monthOpt.getAsString().trim());
                } catch (Exception e) {
                    event.reply("  Month must look like `2026-10`.").setEphemeral(true).queue();
                    return;
                }
                deferred(event, true, hook -> hook.editOriginal(MusicService.postRecap(event.getJDA(), month)
                        ? "Recap posted in <#" + MusicService.DAILY_CHANNEL_ID + ">."
                        : "  Could not post the recap. Check DAILY_SONG_CHANNEL_ID.").queue());
            }
            default -> event.reply("  Unknown music subcommand.").setEphemeral(true).queue();
        }
    }

    // ================================================================ components

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("music:")) return;
        String[] p = id.split(":");
        try {
            switch (p[1]) {
                case "vote" -> MusicService.handleVote(event, p[2], Integer.parseInt(p[3]));
                case "listen" -> MusicService.handleListen(event, p[2]);
                case "who" -> MusicGames.handleWhoGuess(event, p[2], Integer.parseInt(p[3]));
                case "sd" -> MusicGames.handleSongdleButton(event, p[2]);
                case "hl" -> MusicGames.handleHigherLower(event, p[2], p[3].equals("hi"));
                case "bat" -> MusicBattle.handleVote(event, Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                default -> event.reply("  This button has expired.").setEphemeral(true).queue();
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            if (!event.isAcknowledged()) event.reply("  Something went wrong with that button.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().equals("music:sdm")) MusicGames.handleSongdleModal(event);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) return;
        MusicGames.onChatGuess(event);
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (event.isFromGuild()) MusicService.onReaction(event);
    }
}
