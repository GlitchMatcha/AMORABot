package com.amore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All persistence for the music features (song pool, daily features, votes, streaks, games, battles). */
public final class MusicRepository {

    public static final String ORIGIN_MEMBER = "member";
    public static final String ORIGIN_IMPORT = "import";
    /** {@code added_by} for songs seeded from a bundled pack (credited to the staff playlist like imports). */
    static final String SEED_CURATOR = "AMORA";
    private static final String KPOP_PACK = "/music/kpop_starter_pack.tsv";

    public static final int VOTE_FIRE = 2;
    public static final int VOTE_LIKE = 1;
    public static final int VOTE_MEH = -1;

    private static volatile MusicRepository instance;
    private final DatabaseManager db;

    public record Song(int songId, String addedBy, String title, String artist, String link, String source,
                       boolean active, long createdAt, long lastFeaturedAt, String displayTitle, String displayArtist,
                       String tags, String origin, long viewCount, long viewCountAt) {
        public boolean isMemberPick() {
            return ORIGIN_MEMBER.equals(origin);
        }

        public String creditLine() {
            return isMemberPick() ? "<@" + addedBy + ">" : "AMORA staff playlist";
        }
    }

    /** A song to bulk-insert as an import: raw title/artist as fetched, plus the cleaned display metadata. */
    public record ImportRow(String title, String artist, String link, SongMetadata.DisplayMeta meta, String tags) {
    }

    public record Feature(String featureKey, String featureDate, int songId, String channelId, String messageId,
                          String threadId, String theme, String sponsorId, boolean settled, long postedAt) {
    }

    public record Tally(int fire, int like, int meh) {
        public int total() {
            return fire + like + meh;
        }

        public int score() {
            return VOTE_FIRE * fire + VOTE_LIKE * like + VOTE_MEH * meh;
        }

        /** Average vote in [-1, 2]; 0 when no votes. */
        public double average() {
            return total() == 0 ? 0 : (double) score() / total();
        }
    }

    public record Stats(int streak, int bestStreak, String lastListenDate, int curatorPoints, int songsFeatured,
                        int gameWins, int hlBest, String hlRunsDate, int hlRuns) {
        static final Stats EMPTY = new Stats(0, 0, null, 0, 0, 0, 0, null, 0);
    }

    public record Sponsor(int id, int songId, String userId, int cost, long createdAt, String status) {
    }

    public record Vinyl(int songId, int copies) {
    }

    public record VoteRow(String userId, int songId, int vote) {
    }

    public record ListenRow(String userId, String date) {
    }

    public record SongdlePlay(String history, boolean solved, boolean finished) {
    }

    public record Battle(int battleId, String status, int round, long roundEndsAt, long startedAt, String channelId,
                         String messageId, int championSong) {
    }

    public record Match(int matchId, int battleId, int round, int slot, int songA, int songB, int winner) {
    }

    private MusicRepository(DatabaseManager db) {
        this.db = db;
        initSchema();
    }

    public static MusicRepository get() {
        if (instance == null) {
            synchronized (MusicRepository.class) {
                if (instance == null) instance = new MusicRepository(DatabaseManager.getInstance());
            }
        }
        return instance;
    }

    // ================================================================ schema

    private void initSchema() {
        String[] ddl = {
                "ALTER TABLE song_suggestions ADD COLUMN IF NOT EXISTS display_title TEXT",
                "ALTER TABLE song_suggestions ADD COLUMN IF NOT EXISTS display_artist TEXT",
                "ALTER TABLE song_suggestions ADD COLUMN IF NOT EXISTS tags TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE song_suggestions ADD COLUMN IF NOT EXISTS origin TEXT NOT NULL DEFAULT 'member'",
                "ALTER TABLE song_suggestions ADD COLUMN IF NOT EXISTS view_count BIGINT NOT NULL DEFAULT -1",
                "ALTER TABLE song_suggestions ADD COLUMN IF NOT EXISTS view_count_at BIGINT NOT NULL DEFAULT 0",
                "ALTER TABLE song_suggestions ADD COLUMN IF NOT EXISTS dedupe_key TEXT",
                "CREATE INDEX IF NOT EXISTS ix_song_suggestions_dedupe ON song_suggestions (dedupe_key) WHERE is_active = 1",
                "CREATE TABLE IF NOT EXISTS song_features (feature_key TEXT PRIMARY KEY, feature_date TEXT NOT NULL, "
                        + "song_id INTEGER NOT NULL, channel_id TEXT, message_id TEXT, thread_id TEXT, theme TEXT, "
                        + "sponsor_id TEXT, settled INTEGER NOT NULL DEFAULT 0, posted_at BIGINT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS ix_song_features_thread ON song_features (thread_id)",
                "CREATE TABLE IF NOT EXISTS song_votes (feature_key TEXT NOT NULL, user_id TEXT NOT NULL, "
                        + "song_id INTEGER NOT NULL, vote INTEGER NOT NULL, voted_at BIGINT NOT NULL, "
                        + "PRIMARY KEY (feature_key, user_id))",
                "CREATE INDEX IF NOT EXISTS ix_song_votes_song ON song_votes (song_id)",
                "CREATE TABLE IF NOT EXISTS song_listens (feature_key TEXT NOT NULL, user_id TEXT NOT NULL, "
                        + "listen_date TEXT NOT NULL, PRIMARY KEY (feature_key, user_id))",
                "CREATE TABLE IF NOT EXISTS song_thread_rewards (feature_key TEXT NOT NULL, user_id TEXT NOT NULL, "
                        + "PRIMARY KEY (feature_key, user_id))",
                "CREATE TABLE IF NOT EXISTS user_music_stats (user_id TEXT PRIMARY KEY, streak INTEGER NOT NULL DEFAULT 0, "
                        + "best_streak INTEGER NOT NULL DEFAULT 0, last_listen_date TEXT, curator_points INTEGER NOT NULL DEFAULT 0, "
                        + "songs_featured INTEGER NOT NULL DEFAULT 0, game_wins INTEGER NOT NULL DEFAULT 0, "
                        + "hl_best INTEGER NOT NULL DEFAULT 0, hl_runs_date TEXT, hl_runs INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS song_sponsors (id SERIAL PRIMARY KEY, song_id INTEGER NOT NULL, user_id TEXT NOT NULL, "
                        + "cost INTEGER NOT NULL, created_at BIGINT NOT NULL, status TEXT NOT NULL, feature_key TEXT)",
                "CREATE TABLE IF NOT EXISTS user_vinyls (user_id TEXT NOT NULL, song_id INTEGER NOT NULL, "
                        + "copies INTEGER NOT NULL DEFAULT 1, first_at BIGINT NOT NULL, PRIMARY KEY (user_id, song_id))",
                "CREATE TABLE IF NOT EXISTS songdle_plays (play_date TEXT NOT NULL, user_id TEXT NOT NULL, "
                        + "history TEXT NOT NULL DEFAULT '', solved INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (play_date, user_id))",
                "CREATE TABLE IF NOT EXISTS song_battles (battle_id SERIAL PRIMARY KEY, status TEXT NOT NULL, round INTEGER NOT NULL, "
                        + "round_ends_at BIGINT NOT NULL, started_at BIGINT NOT NULL, channel_id TEXT NOT NULL, message_id TEXT, "
                        + "champion_song INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS battle_matches (match_id SERIAL PRIMARY KEY, battle_id INTEGER NOT NULL, "
                        + "round INTEGER NOT NULL, slot INTEGER NOT NULL, song_a INTEGER NOT NULL, song_b INTEGER NOT NULL, "
                        + "winner INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS battle_votes (match_id INTEGER NOT NULL, user_id TEXT NOT NULL, choice INTEGER NOT NULL, "
                        + "PRIMARY KEY (match_id, user_id))"
        };
        try (Statement stmt = db.createStatement()) {
            for (String sql : ddl) stmt.execute(sql);
        } catch (SQLException e) {
            System.out.println("❌ Failed to initialize music tables.");
            e.printStackTrace();
            return;
        }
        backfillOrigins();
        backfillDisplayMetadata();
        seedPack("music_seed_kpop_v1", KPOP_PACK);
    }

    /**
     * One-time insert of a bundled pack (tab-separated: YouTube video ID, artist, title, tags).
     * Songs already in the pool (same link or same artist+title) are skipped; the state flag is set only on success.
     */
    private void seedPack(String stateKey, String resource) {
        if ("done".equals(db.getBotState(stateKey))) return;
        List<ImportRow> rows = new ArrayList<>();
        int skipped = 0;
        try (InputStream in = MusicRepository.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                String tags = f.length == 4 ? SongMetadata.parseTags(f[3]) : null;
                if (tags == null) throw new IllegalStateException("bad line in " + resource + ": " + line);
                String link = "https://www.youtube.com/watch?v=" + f[0].trim();
                SongMetadata.DisplayMeta meta = new SongMetadata.DisplayMeta(f[2].trim(), f[1].trim());
                if (activeSongByLink(link) != null
                        || activeSongByDedupeKey(SongMetadata.dedupeKey(meta.title(), meta.artist())) != null) {
                    skipped++;
                    continue;
                }
                rows.add(new ImportRow(meta.title(), meta.artist(), link, meta, tags));
            }
        } catch (Exception e) {
            System.out.println("❌ Failed to read song pack " + resource);
            e.printStackTrace();
            return;
        }
        int added = addImportedSongs(SEED_CURATOR, rows);
        if (added != rows.size()) return; // insert failed and was rolled back: retry on next boot
        db.setBotState(stateKey, "done");
        System.out.println("✦ Seeded " + resource + ": " + added + " songs added, " + skipped + " already in the pool.");
    }

    /** Rows bulk-inserted by a playlist import share (added_by, created_at); mark them as imports once. */
    private void backfillOrigins() {
        if ("done".equals(db.getBotState("music_origin_backfill"))) return;
        String sql = "UPDATE song_suggestions s SET origin = 'import' FROM ("
                + "SELECT added_by, created_at FROM song_suggestions GROUP BY added_by, created_at HAVING COUNT(*) > 1"
                + ") g WHERE s.added_by = g.added_by AND s.created_at = g.created_at";
        try (Statement stmt = db.createStatement()) {
            stmt.executeUpdate(sql);
            db.setBotState("music_origin_backfill", "done");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void backfillDisplayMetadata() {
        List<Song> pending = new ArrayList<>();
        try (PreparedStatement ps = db.prepare("SELECT * FROM song_suggestions WHERE display_title IS NULL OR dedupe_key IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) pending.add(map(rs));
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }
        if (pending.isEmpty()) return;
        try (PreparedStatement ps = db.prepare(
                "UPDATE song_suggestions SET display_title = ?, display_artist = ?, dedupe_key = ? WHERE song_id = ?")) {
            for (Song s : pending) {
                SongMetadata.DisplayMeta meta = ORIGIN_IMPORT.equals(s.origin())
                        ? SongMetadata.fromYouTube(s.title(), s.artist())
                        : SongMetadata.fromMember(s.title(), s.artist());
                ps.setString(1, meta.title());
                ps.setString(2, meta.artist());
                ps.setString(3, SongMetadata.dedupeKey(meta.title(), meta.artist()));
                ps.setInt(4, s.songId());
                ps.addBatch();
            }
            ps.executeBatch();
            System.out.println("✦ Backfilled display metadata for " + pending.size() + " songs.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ================================================================ songs

    private static Song map(ResultSet rs) throws SQLException {
        String displayTitle = rs.getString("display_title");
        String displayArtist = rs.getString("display_artist");
        return new Song(
                rs.getInt("song_id"), rs.getString("added_by"), rs.getString("title"), rs.getString("artist"),
                rs.getString("link"), rs.getString("source"), rs.getInt("is_active") == 1,
                rs.getLong("created_at"), rs.getLong("last_featured_at"),
                displayTitle == null ? rs.getString("title") : displayTitle,
                displayArtist == null ? rs.getString("artist") : displayArtist,
                rs.getString("tags"), rs.getString("origin"), rs.getLong("view_count"), rs.getLong("view_count_at"));
    }

    private List<Song> songs(String sql, Object... params) {
        List<Song> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    private Song song(String sql, Object... params) {
        List<Song> list = songs(sql, params);
        return list.isEmpty() ? null : list.get(0);
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Integer v) ps.setInt(i + 1, v);
            else if (p instanceof Long v) ps.setLong(i + 1, v);
            else ps.setString(i + 1, p == null ? null : p.toString());
        }
    }

    private int update(String sql, Object... params) {
        try (PreparedStatement ps = db.prepare(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private int intQuery(String sql, Object... params) {
        try (PreparedStatement ps = db.prepare(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public Song songById(int songId) {
        return song("SELECT * FROM song_suggestions WHERE song_id = ?", songId);
    }

    public Song activeSongByLink(String link) {
        return song("SELECT * FROM song_suggestions WHERE LOWER(link) = LOWER(?) AND is_active = 1 LIMIT 1", link);
    }

    public Song activeSongByDedupeKey(String key) {
        if (key == null || key.isEmpty()) return null;
        return song("SELECT * FROM song_suggestions WHERE dedupe_key = ? AND is_active = 1 LIMIT 1", key);
    }

    public Song addMemberSong(String addedBy, String title, String artist, String link, String source, String tags) {
        SongMetadata.DisplayMeta meta = SongMetadata.fromMember(title, artist);
        return song("INSERT INTO song_suggestions (added_by, title, artist, link, source, is_active, created_at, "
                        + "last_featured_at, display_title, display_artist, dedupe_key, tags, origin) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?, 0, ?, ?, ?, ?, 'member') RETURNING *",
                addedBy, title, artist, link, source, System.currentTimeMillis(),
                meta.title(), meta.artist(), SongMetadata.dedupeKey(meta.title(), meta.artist()), tags);
    }

    /** Inserts imported songs in one transaction; returns the number inserted (0 if it was rolled back). */
    public int addImportedSongs(String importedBy, List<ImportRow> rows) {
        if (rows.isEmpty()) return 0;
        Connection conn = db.ensureConnected();
        String sql = "INSERT INTO song_suggestions (added_by, title, artist, link, source, is_active, created_at, "
                + "last_featured_at, display_title, display_artist, dedupe_key, tags, origin) "
                + "VALUES (?, ?, ?, ?, 'YouTube', 1, ?, 0, ?, ?, ?, ?, 'import')";
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                long now = System.currentTimeMillis();
                for (ImportRow row : rows) {
                    ps.setString(1, importedBy);
                    ps.setString(2, row.title());
                    ps.setString(3, row.artist());
                    ps.setString(4, row.link());
                    ps.setLong(5, now);
                    ps.setString(6, row.meta().title());
                    ps.setString(7, row.meta().artist());
                    ps.setString(8, SongMetadata.dedupeKey(row.meta().title(), row.meta().artist()));
                    ps.setString(9, row.tags());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                return rows.size();
            } catch (SQLException e) {
                conn.rollback();
                e.printStackTrace();
                return 0;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public List<Song> allActiveSongs() {
        return songs("SELECT * FROM song_suggestions WHERE is_active = 1 ORDER BY created_at DESC, song_id DESC");
    }

    public int countActive() {
        return intQuery("SELECT COUNT(*) FROM song_suggestions WHERE is_active = 1");
    }

    public int countMemberAddsSince(String userId, long since) {
        return intQuery("SELECT COUNT(*) FROM song_suggestions WHERE added_by = ? AND origin = 'member' AND created_at >= ?",
                userId, since);
    }

    public Song randomActiveSong() {
        return song("SELECT * FROM song_suggestions WHERE is_active = 1 ORDER BY RANDOM() LIMIT 1");
    }

    public List<Song> randomActiveSongs(int limit) {
        return songs("SELECT * FROM song_suggestions WHERE is_active = 1 ORDER BY RANDOM() LIMIT ?", limit);
    }

    /** Least-recently featured active songs, optionally restricted to a tag. */
    public List<Song> leastRecentlyFeatured(String tag, int limit) {
        if (tag == null) {
            return songs("SELECT * FROM song_suggestions WHERE is_active = 1 ORDER BY last_featured_at ASC, RANDOM() LIMIT ?", limit);
        }
        return songs("SELECT * FROM song_suggestions WHERE is_active = 1 AND tags LIKE ? "
                + "ORDER BY last_featured_at ASC, RANDOM() LIMIT ?", "%," + tag + ",%", limit);
    }

    public Song randomMemberSong() {
        return song("SELECT * FROM song_suggestions WHERE is_active = 1 AND origin = 'member' ORDER BY RANDOM() LIMIT 1");
    }

    /** Up to {@code limit} random distinct curators of active member songs, excluding one user. */
    public List<String> randomCurators(String excludeUserId, int limit) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare("SELECT added_by FROM (SELECT DISTINCT added_by FROM song_suggestions "
                + "WHERE is_active = 1 AND origin = 'member' AND added_by <> ?) c ORDER BY RANDOM() LIMIT ?")) {
            bind(ps, excludeUserId, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public List<Song> randomYouTubeSongs(int limit) {
        return songs("SELECT * FROM song_suggestions WHERE is_active = 1 AND source = 'YouTube' ORDER BY RANDOM() LIMIT ?", limit);
    }

    public void setViewCount(int songId, long views, long at) {
        update("UPDATE song_suggestions SET view_count = ?, view_count_at = ? WHERE song_id = ?", views, at, songId);
    }

    public boolean deactivate(int songId) {
        return update("UPDATE song_suggestions SET is_active = 0 WHERE song_id = ? AND is_active = 1", songId) > 0;
    }

    public void markFeatured(int songId, long at) {
        update("UPDATE song_suggestions SET last_featured_at = ? WHERE song_id = ?", at, songId);
    }

    public void setTags(int songId, String tags) {
        update("UPDATE song_suggestions SET tags = ? WHERE song_id = ?", tags, songId);
    }

    public List<Song> songsAddedBy(String userId) {
        return songs("SELECT * FROM song_suggestions WHERE added_by = ? AND origin = 'member' AND is_active = 1 "
                + "ORDER BY created_at DESC", userId);
    }

    // ================================================================ features

    private static Feature mapFeature(ResultSet rs) throws SQLException {
        return new Feature(rs.getString("feature_key"), rs.getString("feature_date"), rs.getInt("song_id"),
                rs.getString("channel_id"), rs.getString("message_id"), rs.getString("thread_id"),
                rs.getString("theme"), rs.getString("sponsor_id"), rs.getInt("settled") == 1, rs.getLong("posted_at"));
    }

    private List<Feature> features(String sql, Object... params) {
        List<Feature> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapFeature(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public void insertFeature(Feature f) {
        update("INSERT INTO song_features (feature_key, feature_date, song_id, channel_id, message_id, thread_id, theme, "
                        + "sponsor_id, settled, posted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
                f.featureKey(), f.featureDate(), f.songId(), f.channelId(), f.messageId(), f.threadId(), f.theme(),
                f.sponsorId(), f.postedAt());
    }

    public void setFeatureThread(String featureKey, String threadId) {
        update("UPDATE song_features SET thread_id = ? WHERE feature_key = ?", threadId, featureKey);
    }

    public Feature feature(String featureKey) {
        List<Feature> list = features("SELECT * FROM song_features WHERE feature_key = ?", featureKey);
        return list.isEmpty() ? null : list.get(0);
    }

    public Feature featureByThread(String threadId) {
        List<Feature> list = features("SELECT * FROM song_features WHERE thread_id = ?", threadId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Feature> unsettledFeaturesBefore(long postedBefore) {
        return features("SELECT * FROM song_features WHERE settled = 0 AND posted_at < ? ORDER BY posted_at", postedBefore);
    }

    /** Marks settled; returns false if another caller settled it first. */
    public boolean markSettled(String featureKey) {
        return update("UPDATE song_features SET settled = 1 WHERE feature_key = ? AND settled = 0", featureKey) > 0;
    }

    public List<Feature> featuresPostedBetween(long fromInclusive, long toExclusive) {
        return features("SELECT * FROM song_features WHERE posted_at >= ? AND posted_at < ? ORDER BY posted_at",
                fromInclusive, toExclusive);
    }

    public List<Integer> songIdsFeaturedSince(long since) {
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare("SELECT DISTINCT song_id FROM song_features WHERE posted_at >= ?")) {
            bind(ps, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public int countFeaturedSongs() {
        return intQuery("SELECT COUNT(DISTINCT f.song_id) FROM song_features f JOIN song_suggestions s ON s.song_id = f.song_id "
                + "WHERE s.is_active = 1");
    }

    public Song randomFeaturedSong() {
        return song("SELECT s.* FROM song_suggestions s WHERE s.is_active = 1 AND s.song_id IN "
                + "(SELECT song_id FROM song_features) ORDER BY RANDOM() LIMIT 1");
    }

    public int countFeaturesOfSong(int songId) {
        return intQuery("SELECT COUNT(*) FROM song_features WHERE song_id = ?", songId);
    }

    // ================================================================ votes

    public void upsertVote(String featureKey, String userId, int songId, int vote) {
        update("INSERT INTO song_votes (feature_key, user_id, song_id, vote, voted_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (feature_key, user_id) DO UPDATE SET vote = EXCLUDED.vote, voted_at = EXCLUDED.voted_at",
                featureKey, userId, songId, vote, System.currentTimeMillis());
    }

    private static final String TALLY_COLUMNS = "COUNT(*) FILTER (WHERE vote = 2), COUNT(*) FILTER (WHERE vote = 1), "
            + "COUNT(*) FILTER (WHERE vote = -1)";

    public Tally featureTally(String featureKey) {
        return tally("SELECT " + TALLY_COLUMNS + " FROM song_votes WHERE feature_key = ?", featureKey);
    }

    /** Tally for a feature, excluding votes by one user (the submitter). */
    public Tally featureTallyExcluding(String featureKey, String excludedUserId) {
        return tally("SELECT " + TALLY_COLUMNS + " FROM song_votes WHERE feature_key = ? AND user_id <> ?",
                featureKey, excludedUserId);
    }

    public Tally songTally(int songId) {
        return tally("SELECT " + TALLY_COLUMNS + " FROM song_votes WHERE song_id = ?", songId);
    }

    private Tally tally(String sql, Object... params) {
        try (PreparedStatement ps = db.prepare(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Tally(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return new Tally(0, 0, 0);
    }

    public Map<Integer, Tally> songTallies() {
        Map<Integer, Tally> out = new HashMap<>();
        try (PreparedStatement ps = db.prepare("SELECT song_id, " + TALLY_COLUMNS + " FROM song_votes GROUP BY song_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getInt(1), new Tally(rs.getInt(2), rs.getInt(3), rs.getInt(4)));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public List<VoteRow> allVotes() {
        List<VoteRow> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare("SELECT user_id, song_id, vote FROM song_votes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new VoteRow(rs.getString(1), rs.getInt(2), rs.getInt(3)));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public int countVotesBetween(long from, long to) {
        return intQuery("SELECT COUNT(*) FROM song_votes WHERE voted_at >= ? AND voted_at < ?", from, to);
    }

    // ================================================================ listens & stats

    /** Returns true only for the first listen of this user on this feature. */
    public boolean recordListen(String featureKey, String userId, String date) {
        return update("INSERT INTO song_listens (feature_key, user_id, listen_date) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                featureKey, userId, date) > 0;
    }

    public List<ListenRow> listensBetweenDates(String fromDate, String toDateInclusive) {
        List<ListenRow> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare("SELECT DISTINCT user_id, listen_date FROM song_listens "
                + "WHERE listen_date >= ? AND listen_date <= ? ORDER BY user_id, listen_date")) {
            bind(ps, fromDate, toDateInclusive);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new ListenRow(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public int countListensBetweenDates(String fromDate, String toDateInclusive) {
        return intQuery("SELECT COUNT(*) FROM song_listens WHERE listen_date >= ? AND listen_date <= ?", fromDate, toDateInclusive);
    }

    private void ensureStats(String userId) {
        update("INSERT INTO user_music_stats (user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
    }

    public Stats stats(String userId) {
        try (PreparedStatement ps = db.prepare("SELECT * FROM user_music_stats WHERE user_id = ?")) {
            bind(ps, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Stats(rs.getInt("streak"), rs.getInt("best_streak"), rs.getString("last_listen_date"),
                            rs.getInt("curator_points"), rs.getInt("songs_featured"), rs.getInt("game_wins"),
                            rs.getInt("hl_best"), rs.getString("hl_runs_date"), rs.getInt("hl_runs"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Stats.EMPTY;
    }

    public void saveStreak(String userId, int streak, int best, String date) {
        ensureStats(userId);
        update("UPDATE user_music_stats SET streak = ?, best_streak = ?, last_listen_date = ? WHERE user_id = ?",
                streak, best, date, userId);
    }

    public void addCuratorPoints(String userId, int points, int featuredDelta) {
        ensureStats(userId);
        update("UPDATE user_music_stats SET curator_points = curator_points + ?, songs_featured = songs_featured + ? "
                + "WHERE user_id = ?", points, featuredDelta, userId);
    }

    public void addGameWin(String userId) {
        ensureStats(userId);
        update("UPDATE user_music_stats SET game_wins = game_wins + 1 WHERE user_id = ?", userId);
    }

    public void saveHigherLower(String userId, int best, String runsDate, int runs) {
        ensureStats(userId);
        update("UPDATE user_music_stats SET hl_best = ?, hl_runs_date = ?, hl_runs = ? WHERE user_id = ?",
                best, runsDate, runs, userId);
    }

    public List<String> topCurators() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare("SELECT user_id, curator_points, songs_featured FROM user_music_stats "
                + "WHERE curator_points > 0 ORDER BY curator_points DESC, songs_featured DESC LIMIT 10");
             ResultSet rs = ps.executeQuery()) {
            int rank = 1;
            while (rs.next()) {
                out.add("`#" + rank++ + "` <@" + rs.getString(1) + "> - **" + rs.getInt(2) + " Curator Points** ("
                        + rs.getInt(3) + " featured)");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    /** Returns true only for the first reward claim of this user in this feature's thread. */
    public boolean claimThreadReward(String featureKey, String userId) {
        return update("INSERT INTO song_thread_rewards (feature_key, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                featureKey, userId) > 0;
    }

    public int countThreadRewards(String featureKey) {
        return intQuery("SELECT COUNT(*) FROM song_thread_rewards WHERE feature_key = ?", featureKey);
    }

    // ================================================================ sponsors

    private List<Sponsor> sponsors(String sql, Object... params) {
        List<Sponsor> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Sponsor(rs.getInt("id"), rs.getInt("song_id"), rs.getString("user_id"), rs.getInt("cost"),
                            rs.getLong("created_at"), rs.getString("status")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public Sponsor latestSponsorBy(String userId) {
        List<Sponsor> list = sponsors("SELECT * FROM song_sponsors WHERE user_id = ? AND status <> 'refunded' "
                + "ORDER BY created_at DESC LIMIT 1", userId);
        return list.isEmpty() ? null : list.get(0);
    }

    public boolean songHasPendingSponsor(int songId) {
        return intQuery("SELECT COUNT(*) FROM song_sponsors WHERE song_id = ? AND status = 'pending'", songId) > 0;
    }

    public Sponsor insertSponsor(int songId, String userId, int cost) {
        List<Sponsor> list = sponsors("INSERT INTO song_sponsors (song_id, user_id, cost, created_at, status) "
                + "VALUES (?, ?, ?, ?, 'pending') RETURNING *", songId, userId, cost, System.currentTimeMillis());
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Sponsor> pendingSponsors() {
        return sponsors("SELECT * FROM song_sponsors WHERE status = 'pending' ORDER BY created_at, id");
    }

    public List<Sponsor> pendingSponsorsForSong(int songId) {
        return sponsors("SELECT * FROM song_sponsors WHERE song_id = ? AND status = 'pending'", songId);
    }

    /** Moves a pending sponsorship to a final status; false if it was no longer pending. */
    public boolean closeSponsor(int id, String status, String featureKey) {
        return update("UPDATE song_sponsors SET status = ?, feature_key = ? WHERE id = ? AND status = 'pending'",
                status, featureKey, id) > 0;
    }

    // ================================================================ vinyls

    /** Adds a copy and returns the copy count after the insert. */
    public int addVinyl(String userId, int songId) {
        return intQuery("INSERT INTO user_vinyls (user_id, song_id, copies, first_at) VALUES (?, ?, 1, ?) "
                        + "ON CONFLICT (user_id, song_id) DO UPDATE SET copies = user_vinyls.copies + 1 RETURNING copies",
                userId, songId, System.currentTimeMillis());
    }

    public boolean hasVinyl(String userId, int songId) {
        return intQuery("SELECT COUNT(*) FROM user_vinyls WHERE user_id = ? AND song_id = ?", userId, songId) > 0;
    }

    public List<Vinyl> vinyls(String userId) {
        List<Vinyl> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare("SELECT song_id, copies FROM user_vinyls WHERE user_id = ? ORDER BY first_at DESC")) {
            bind(ps, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Vinyl(rs.getInt(1), rs.getInt(2)));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    // ================================================================ songdle

    public SongdlePlay songdlePlay(String date, String userId) {
        try (PreparedStatement ps = db.prepare("SELECT history, solved, finished FROM songdle_plays WHERE play_date = ? AND user_id = ?")) {
            bind(ps, date, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new SongdlePlay(rs.getString(1), rs.getInt(2) == 1, rs.getInt(3) == 1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Appends one guess result to the user's history, only while the play is unfinished.
     * Returns the stored play after the update, or null if the play was already finished.
     */
    public SongdlePlay appendSongdleGuess(String date, String userId, char result, int maxGuesses) {
        update("INSERT INTO songdle_plays (play_date, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", date, userId);
        boolean solved = result == 'o';
        try (PreparedStatement ps = db.prepare("UPDATE songdle_plays SET history = history || ?, "
                + "solved = CASE WHEN ? = 1 THEN 1 ELSE solved END, "
                + "finished = CASE WHEN ? = 1 OR LENGTH(history) + 1 >= ? THEN 1 ELSE 0 END "
                + "WHERE play_date = ? AND user_id = ? AND finished = 0 RETURNING history, solved, finished")) {
            bind(ps, String.valueOf(result), solved ? 1 : 0, solved ? 1 : 0, maxGuesses, date, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new SongdlePlay(rs.getString(1), rs.getInt(2) == 1, rs.getInt(3) == 1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ================================================================ battles

    private List<Battle> battles(String sql, Object... params) {
        List<Battle> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Battle(rs.getInt("battle_id"), rs.getString("status"), rs.getInt("round"),
                            rs.getLong("round_ends_at"), rs.getLong("started_at"), rs.getString("channel_id"),
                            rs.getString("message_id"), rs.getInt("champion_song")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public Battle activeBattle() {
        List<Battle> list = battles("SELECT * FROM song_battles WHERE status = 'active' ORDER BY battle_id DESC LIMIT 1");
        return list.isEmpty() ? null : list.get(0);
    }

    public Battle latestBattle() {
        List<Battle> list = battles("SELECT * FROM song_battles ORDER BY battle_id DESC LIMIT 1");
        return list.isEmpty() ? null : list.get(0);
    }

    public Battle createBattle(String channelId, long roundEndsAt) {
        List<Battle> list = battles("INSERT INTO song_battles (status, round, round_ends_at, started_at, channel_id) "
                + "VALUES ('active', 1, ?, ?, ?) RETURNING *", roundEndsAt, System.currentTimeMillis(), channelId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateBattleRound(int battleId, int round, long roundEndsAt, String messageId) {
        update("UPDATE song_battles SET round = ?, round_ends_at = ?, message_id = ? WHERE battle_id = ?",
                round, roundEndsAt, messageId, battleId);
    }

    public void setBattleMessage(int battleId, String messageId) {
        update("UPDATE song_battles SET message_id = ? WHERE battle_id = ?", messageId, battleId);
    }

    public void finishBattle(int battleId, int championSong) {
        update("UPDATE song_battles SET status = 'finished', champion_song = ? WHERE battle_id = ?", championSong, battleId);
    }

    public void insertMatch(int battleId, int round, int slot, int songA, int songB) {
        update("INSERT INTO battle_matches (battle_id, round, slot, song_a, song_b) VALUES (?, ?, ?, ?, ?)",
                battleId, round, slot, songA, songB);
    }

    public List<Match> matches(int battleId, int round) {
        List<Match> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare("SELECT * FROM battle_matches WHERE battle_id = ? AND round = ? ORDER BY slot")) {
            bind(ps, battleId, round);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Match(rs.getInt("match_id"), rs.getInt("battle_id"), rs.getInt("round"), rs.getInt("slot"),
                            rs.getInt("song_a"), rs.getInt("song_b"), rs.getInt("winner")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public Match match(int matchId) {
        try (PreparedStatement ps = db.prepare("SELECT * FROM battle_matches WHERE match_id = ?")) {
            bind(ps, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Match(rs.getInt("match_id"), rs.getInt("battle_id"), rs.getInt("round"), rs.getInt("slot"),
                            rs.getInt("song_a"), rs.getInt("song_b"), rs.getInt("winner"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void setMatchWinner(int matchId, int songId) {
        update("UPDATE battle_matches SET winner = ? WHERE match_id = ?", songId, matchId);
    }

    public void upsertBattleVote(int matchId, String userId, int choice) {
        update("INSERT INTO battle_votes (match_id, user_id, choice) VALUES (?, ?, ?) "
                + "ON CONFLICT (match_id, user_id) DO UPDATE SET choice = EXCLUDED.choice", matchId, userId, choice);
    }

    /** [votes for A, votes for B] */
    public int[] matchVotes(int matchId) {
        try (PreparedStatement ps = db.prepare("SELECT COUNT(*) FILTER (WHERE choice = 0), COUNT(*) FILTER (WHERE choice = 1) "
                + "FROM battle_votes WHERE match_id = ?")) {
            bind(ps, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new int[]{rs.getInt(1), rs.getInt(2)};
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return new int[]{0, 0};
    }
}
