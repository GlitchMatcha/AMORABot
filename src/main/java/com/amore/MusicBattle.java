package com.amore;

import java.awt.Color;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

/** Weekly Song Battle: a bracket of top-rated songs, one round per day, community votes decide each match. */
public final class MusicBattle {

    private MusicBattle() {
    }

    static final String BATTLE_CHANNEL_ID = firstNonBlank(System.getenv("MUSIC_BATTLE_CHANNEL_ID"), MusicService.DAILY_CHANNEL_ID);
    static final String CURATOR_ROLE_ID = blankToNull(System.getenv("MUSIC_CURATOR_ROLE_ID"));
    static final long ROUND_MS = TimeUnit.HOURS.toMillis(24);
    static final int CHAMPION_SPARKS = 50;
    static final int CHAMPION_CURATOR_POINTS = 20;
    private static final Color BATTLE_COLOR = new Color(255, 140, 0);

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String firstNonBlank(String a, String b) {
        String x = blankToNull(a);
        return x != null ? x : b;
    }

    private static MusicRepository repo() {
        return MusicRepository.get();
    }

    /** Called every minute by the music scheduler. */
    static synchronized void tick(JDA jda) {
        MusicRepository.Battle battle = repo().activeBattle();
        long now = System.currentTimeMillis();
        if (battle != null) {
            if (now >= battle.roundEndsAt()) resolveRound(jda, battle);
            return;
        }
        ZonedDateTime utc = ZonedDateTime.now(ZoneOffset.UTC);
        if (utc.getDayOfWeek() != DayOfWeek.MONDAY || utc.getHour() < MusicService.POST_HOUR_UTC) return;
        MusicRepository.Battle latest = repo().latestBattle();
        if (latest != null && now - latest.startedAt() < TimeUnit.DAYS.toMillis(6)) return;
        String err = start(jda);
        if (err != null) System.out.println("✦ Weekly song battle skipped: " + err);
    }

    /** Starts a new battle. Returns an error message, or null on success. */
    static synchronized String start(JDA jda) {
        if (BATTLE_CHANNEL_ID == null) return "No battle channel configured (MUSIC_BATTLE_CHANNEL_ID or DAILY_SONG_CHANNEL_ID).";
        TextChannel channel = jda.getTextChannelById(BATTLE_CHANNEL_ID);
        if (channel == null) return "Battle channel not found.";
        if (repo().activeBattle() != null) return "A song battle is already running.";

        List<Integer> seeds = seeds();
        if (seeds.size() < 4) return "Not enough rated songs yet (need at least 4 with votes).";
        int size = seeds.size() >= 16 ? 16 : seeds.size() >= 8 ? 8 : 4;
        seeds = seeds.subList(0, size);

        MusicRepository.Battle battle = repo().createBattle(channel.getId(), System.currentTimeMillis() + ROUND_MS);
        if (battle == null) return "Could not create the battle.";
        for (int i = 0; i < size / 2; i++) {
            repo().insertMatch(battle.battleId(), 1, i, seeds.get(i), seeds.get(size - 1 - i)); // 1 vs N, 2 vs N-1, ...
        }
        postRound(channel, battle.battleId(), 1, battle.roundEndsAt());
        return null;
    }

    /** Songs featured in the last 30 days ranked by rating, topped up with the best-rated songs overall. */
    private static List<Integer> seeds() {
        Map<Integer, MusicRepository.Tally> tallies = repo().songTallies();
        Comparator<Integer> byRating = Comparator.<Integer>comparingInt(id -> tallies.get(id).score())
                .thenComparingInt(id -> tallies.get(id).total()).reversed();

        List<Integer> recent = new ArrayList<>(repo().songIdsFeaturedSince(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)));
        recent.removeIf(id -> !tallies.containsKey(id) || !isActive(id));
        recent.sort(byRating);

        Set<Integer> ordered = new LinkedHashSet<>(recent);
        if (ordered.size() < 16) {
            List<Integer> overall = new ArrayList<>(tallies.keySet());
            overall.removeIf(id -> !isActive(id));
            overall.sort(byRating);
            for (Integer id : overall) {
                if (ordered.size() >= 16) break;
                ordered.add(id);
            }
        }
        return new ArrayList<>(ordered);
    }

    private static boolean isActive(int songId) {
        MusicRepository.Song s = repo().songById(songId);
        return s != null && s.active();
    }

    private static String roundName(int matches) {
        return switch (matches) {
            case 8 -> "Round of 16";
            case 4 -> "Quarterfinals";
            case 2 -> "Semifinals";
            default -> "Grand Final";
        };
    }

    private static void postRound(TextChannel channel, int battleId, int round, long endsAt) {
        List<MusicRepository.Match> matches = repo().matches(battleId, round);
        Message msg = channel.sendMessageEmbeds(roundEmbed(matches, endsAt, false).build())
                .setComponents(roundRows(matches, false)).complete();
        repo().updateBattleRound(battleId, round, endsAt, msg.getId());
        channel.sendMessage("⚔️ **Song Battle · " + roundName(matches.size()) + "** is live! Vote for your favourites above. 🎧")
                .queue(s -> {}, e -> {});
    }

    private static EmbedBuilder roundEmbed(List<MusicRepository.Match> matches, long endsAt, boolean closed) {
        StringBuilder desc = new StringBuilder(closed
                ? "Voting closed. Winners advance!\n\n"
                : "Pick a side in every match. Voting ends <t:" + endsAt / 1000 + ":R>.\n\n");
        int n = 1;
        for (MusicRepository.Match m : matches) {
            MusicRepository.Song a = repo().songById(m.songA());
            MusicRepository.Song b = repo().songById(m.songB());
            int[] v = repo().matchVotes(m.matchId());
            desc.append("**Match ").append(n++).append("**\n")
                    .append(m.winner() == m.songA() ? "🏆 " : "🅰️ ").append(label(a)).append(" · `").append(v[0]).append("`\n")
                    .append(m.winner() == m.songB() ? "🏆 " : "🅱️ ").append(label(b)).append(" · `").append(v[1]).append("`\n\n");
        }
        return new EmbedBuilder()
                .setColor(BATTLE_COLOR)
                .setTitle("⚔️ WEEKLY SONG BATTLE · " + roundName(matches.size()).toUpperCase())
                .setDescription(desc.toString())
                .setFooter("Ties go to the better-rated song", null);
    }

    private static String label(MusicRepository.Song s) {
        return s == null ? "_removed song_" : "**" + SongMetadata.truncate(s.displayTitle(), 45) + "** — " + SongMetadata.truncate(s.displayArtist(), 25);
    }

    private static List<ActionRow> roundRows(List<MusicRepository.Match> matches, boolean closed) {
        List<ActionRow> rows = new ArrayList<>();
        List<Button> row = new ArrayList<>();
        int n = 1;
        for (MusicRepository.Match m : matches) {
            MusicRepository.Song a = repo().songById(m.songA());
            MusicRepository.Song b = repo().songById(m.songB());
            row.add(Button.primary("music:bat:" + m.matchId() + ":0", n + "🅰️ " + shortTitle(a)).withDisabled(closed));
            row.add(Button.secondary("music:bat:" + m.matchId() + ":1", n + "🅱️ " + shortTitle(b)).withDisabled(closed));
            n++;
            if (row.size() == 4) {
                rows.add(ActionRow.of(row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(ActionRow.of(row));
        return rows;
    }

    private static String shortTitle(MusicRepository.Song s) {
        return s == null ? "removed" : SongMetadata.truncate(s.displayTitle(), 30);
    }

    public static void handleVote(ButtonInteractionEvent event, int matchId, int choice) {
        event.deferReply(true).queue();
        MusicRepository.Match match = repo().match(matchId);
        MusicRepository.Battle battle = repo().activeBattle();
        if (match == null || battle == null || match.battleId() != battle.battleId() || match.round() != battle.round() || match.winner() != 0) {
            event.getHook().sendMessage("  Voting on this match has closed.").queue();
            return;
        }
        repo().upsertBattleVote(matchId, event.getUser().getId(), choice);
        List<MusicRepository.Match> matches = repo().matches(battle.battleId(), battle.round());
        event.getMessage().editMessageEmbeds(roundEmbed(matches, battle.roundEndsAt(), false).build()).queue(s -> {}, e -> {});
        MusicRepository.Song picked = repo().songById(choice == 0 ? match.songA() : match.songB());
        event.getHook().sendMessage("⚔️ Vote saved for **" + (picked == null ? "that song" : picked.displayTitle())
                + "**. You can switch sides until the round ends.").queue();
    }

    private static void resolveRound(JDA jda, MusicRepository.Battle battle) {
        TextChannel channel = jda.getTextChannelById(battle.channelId());
        List<MusicRepository.Match> matches = repo().matches(battle.battleId(), battle.round());
        Map<Integer, MusicRepository.Tally> tallies = repo().songTallies();
        List<Integer> winners = new ArrayList<>();
        Map<Integer, MusicRepository.Match> resolved = new HashMap<>();
        for (MusicRepository.Match m : matches) {
            int winner = m.winner();
            if (winner == 0) {
                int[] v = repo().matchVotes(m.matchId());
                if (v[0] != v[1]) {
                    winner = v[0] > v[1] ? m.songA() : m.songB();
                } else {
                    int sa = tallies.containsKey(m.songA()) ? tallies.get(m.songA()).score() : 0;
                    int sb = tallies.containsKey(m.songB()) ? tallies.get(m.songB()).score() : 0;
                    winner = sa != sb ? (sa > sb ? m.songA() : m.songB())
                            : (ThreadLocalRandom.current().nextBoolean() ? m.songA() : m.songB());
                }
                repo().setMatchWinner(m.matchId(), winner);
            }
            winners.add(winner);
            resolved.put(m.matchId(), new MusicRepository.Match(m.matchId(), m.battleId(), m.round(), m.slot(), m.songA(), m.songB(), winner));
        }
        List<MusicRepository.Match> closedMatches = new ArrayList<>();
        for (MusicRepository.Match m : matches) closedMatches.add(resolved.get(m.matchId()));

        if (channel != null && battle.messageId() != null) {
            channel.editMessageEmbedsById(battle.messageId(), roundEmbed(closedMatches, battle.roundEndsAt(), true).build())
                    .setComponents(roundRows(closedMatches, true)).queue(s -> {}, e -> {});
        }

        if (winners.size() == 1) {
            crownChampion(jda, channel, battle, winners.get(0));
            return;
        }
        int nextRound = battle.round() + 1;
        for (int i = 0; i + 1 < winners.size(); i += 2) {
            repo().insertMatch(battle.battleId(), nextRound, i / 2, winners.get(i), winners.get(i + 1));
        }
        long endsAt = System.currentTimeMillis() + ROUND_MS;
        if (channel != null) {
            postRound(channel, battle.battleId(), nextRound, endsAt);
        } else {
            repo().updateBattleRound(battle.battleId(), nextRound, endsAt, null);
        }
    }

    private static void crownChampion(JDA jda, TextChannel channel, MusicRepository.Battle battle, int songId) {
        repo().finishBattle(battle.battleId(), songId);
        MusicRepository.Song song = repo().songById(songId);
        if (song == null || channel == null) return;

        StringBuilder desc = new StringBuilder("🏆 **" + song.displayTitle() + "** by **" + song.displayArtist()
                + "** is this week's champion!\n🎧 " + song.link() + "\n\n");
        if (song.isMemberPick()) {
            DatabaseManager.getInstance().addSparks(song.addedBy(), CHAMPION_SPARKS);
            repo().addCuratorPoints(song.addedBy(), CHAMPION_CURATOR_POINTS, 0);
            MusicService.grantTimedRole(channel.getGuild(), song.addedBy(), CURATOR_ROLE_ID,
                    System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7));
            desc.append("🫶 Curator <@").append(song.addedBy()).append("> earns `+").append(CHAMPION_SPARKS)
                    .append(" Sparks`, `+").append(CHAMPION_CURATOR_POINTS).append(" Curator Points`")
                    .append(CURATOR_ROLE_ID != null ? " and **Curator of the Week** for 7 days!" : "!");
        } else {
            desc.append("_From the AMORA staff playlist._");
        }
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(255, 215, 0))
                .setTitle("👑 SONG BATTLE CHAMPION 👑")
                .setDescription(desc.toString())
                .setFooter("A new battle starts every Monday", null);
        String art = MusicArt.artworkUrl(song.link());
        if (art != null) eb.setImage(art);
        channel.sendMessageEmbeds(eb.build()).queue(s -> {}, e -> {});
    }

    public static String status() {
        MusicRepository.Battle battle = repo().activeBattle();
        if (battle == null) return "No song battle is running. A new one starts every Monday.";
        List<MusicRepository.Match> matches = repo().matches(battle.battleId(), battle.round());
        return "⚔️ **" + roundName(matches.size()) + "** is live in <#" + battle.channelId() + ">, ends <t:" + battle.roundEndsAt() / 1000 + ":R>.";
    }
}
