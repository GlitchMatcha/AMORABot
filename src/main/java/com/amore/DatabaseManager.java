package com.amore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {

    private static final String URL = System.getenv("DATABASE_URL");
    private static DatabaseManager instance;
    private Connection connection;

    public static class PendingTradeSetupRecord {
        public final String setupId;
        public final String senderId;
        public final String targetId;
        public final String selectedOffer;
        public final String selectedRequest;
        public final long expiresAt;

        public PendingTradeSetupRecord(String setupId, String senderId, String targetId,
                                       String selectedOffer, String selectedRequest, long expiresAt) {
            this.setupId = setupId;
            this.senderId = senderId;
            this.targetId = targetId;
            this.selectedOffer = selectedOffer;
            this.selectedRequest = selectedRequest;
            this.expiresAt = expiresAt;
        }
    }

    public static class ActiveTradeRecord {
        public final String tradeId;
        public final String senderId;
        public final String targetId;
        public final String offerItem;
        public final String requestItem;
        public final long expiresAt;

        public ActiveTradeRecord(String tradeId, String senderId, String targetId,
                                 String offerItem, String requestItem, long expiresAt) {
            this.tradeId = tradeId;
            this.senderId = senderId;
            this.targetId = targetId;
            this.offerItem = offerItem;
            this.requestItem = requestItem;
            this.expiresAt = expiresAt;
        }
    }

    public static class SongSuggestionRecord {
        public final int songId;
        public final String addedBy;
        public final String title;
        public final String artist;
        public final String link;
        public final String source;
        public final boolean active;
        public final long createdAt;
        public final long lastFeaturedAt;

        public SongSuggestionRecord(
                int songId, String addedBy, String title, String artist, String link,
                String source, boolean active, long createdAt, long lastFeaturedAt) {
            this.songId = songId;
            this.addedBy = addedBy;
            this.title = title;
            this.artist = artist;
            this.link = link;
            this.source = source;
            this.active = active;
            this.createdAt = createdAt;
            this.lastFeaturedAt = lastFeaturedAt;
        }
    }

    private DatabaseManager() {
    if (URL == null || URL.isBlank()) {
        throw new IllegalStateException("DATABASE_URL environment variable is not set!");
    }

    connect();

    if (connection == null) {
        throw new IllegalStateException("Failed to establish PostgreSQL connection.");
    }

    initializeDatabase();
}

private void connect() {
    try {
        Class.forName("org.postgresql.Driver");
        connection = DriverManager.getConnection(URL);
        System.out.println("✦ PostgreSQL Database Connected Successfully.");
    } catch (Exception e) {
        System.out.println("❌ Failed to connect to the PostgreSQL database.");
        e.printStackTrace();
        connection = null;
    }
}

    private void initializeDatabase() {
    if (connection == null) {
        throw new IllegalStateException("Cannot initialize database because connection is null.");
    }

    String createUsersTable = "CREATE TABLE IF NOT EXISTS users ("
            + "user_id TEXT PRIMARY KEY, "
            + "sparks INTEGER DEFAULT 0, "
            + "points INTEGER DEFAULT 0, "
            + "inventory TEXT DEFAULT '', "
            + "pity INTEGER DEFAULT 0, "
            + "bounties_cleared INTEGER DEFAULT 0, "
            + "urgent_cleared INTEGER DEFAULT 0"
            + ");";

        String createShopTable = "CREATE TABLE IF NOT EXISTS shop_items ("
                + "item_name TEXT PRIMARY KEY, "
                + "secret_link TEXT"
                + ");";

        String createPendingTradeSetupsTable = "CREATE TABLE IF NOT EXISTS pending_trade_setups ("
                + "setup_id TEXT PRIMARY KEY, "
                + "sender_id TEXT NOT NULL, "
                + "target_id TEXT NOT NULL, "
                + "selected_offer TEXT, "
                + "selected_request TEXT, "
                + "expires_at BIGINT NOT NULL"
                + ");";

        String createActiveTradesTable = "CREATE TABLE IF NOT EXISTS active_trades ("
                + "trade_id TEXT PRIMARY KEY, "
                + "sender_id TEXT NOT NULL, "
                + "target_id TEXT NOT NULL, "
                + "offer_item TEXT NOT NULL, "
                + "request_item TEXT NOT NULL, "
                + "expires_at BIGINT NOT NULL"
                + ");";

        String createPendingForgesTable = "CREATE TABLE IF NOT EXISTS pending_forges ("
                + "owner_id TEXT PRIMARY KEY, "
                + "ingredient TEXT NOT NULL, "
                + "expires_at BIGINT NOT NULL"
                + ");";
        String createSongSuggestionsTable = "CREATE TABLE IF NOT EXISTS song_suggestions ("
                + "song_id SERIAL PRIMARY KEY, "
                + "added_by TEXT NOT NULL, "
                + "title TEXT NOT NULL, "
                + "artist TEXT NOT NULL, "
                + "link TEXT NOT NULL, "
                + "source TEXT NOT NULL, "
                + "is_active INTEGER NOT NULL DEFAULT 1, "
                + "created_at BIGINT NOT NULL, "
                + "last_featured_at BIGINT NOT NULL DEFAULT 0"
                + ");";

        String createBotStateTable = "CREATE TABLE IF NOT EXISTS bot_state ("
                + "state_key TEXT PRIMARY KEY, "
                + "state_value TEXT NOT NULL"
                + ");";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createShopTable);
            stmt.execute(createPendingTradeSetupsTable);
            stmt.execute(createActiveTradesTable);
            stmt.execute(createPendingForgesTable);
            stmt.execute(createSongSuggestionsTable);
            stmt.execute(createBotStateTable);
            System.out.println("✦ Core tables, Shop Vault, session tables, and music tables verified (PostgreSQL).");
        } catch (SQLException e) {
        e.printStackTrace();
    }
}
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        String deletePendingTradeSetups = "DELETE FROM pending_trade_setups WHERE expires_at < ?;";
        String deleteActiveTrades = "DELETE FROM active_trades WHERE expires_at < ?;";
        String deletePendingForges = "DELETE FROM pending_forges WHERE expires_at < ?;";

        try (PreparedStatement p1 = connection.prepareStatement(deletePendingTradeSetups);
             PreparedStatement p2 = connection.prepareStatement(deleteActiveTrades);
             PreparedStatement p3 = connection.prepareStatement(deletePendingForges)) {
            p1.setLong(1, now);
            p2.setLong(1, now);
            p3.setLong(1, now);
            p1.executeUpdate();
            p2.executeUpdate();
            p3.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void createNewUser(String userId) {
        String query = "INSERT INTO users "
                + "(user_id, sparks, points, inventory, pity, bounties_cleared, urgent_cleared) "
                + "VALUES (?, 0, 0, '', 0, 0, 0) ON CONFLICT (user_id) DO NOTHING;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void incrementBountyStats(String userId, boolean isUrgent) {
        getPoints(userId); 
        String column = isUrgent ? "urgent_cleared" : "bounties_cleared";
        String query = "UPDATE users SET " + column + " = " + column + " + 1 WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<String> getTopWealth() {
        List<String> top = new ArrayList<>();
        String query = "SELECT user_id, points, sparks FROM users ORDER BY points DESC, sparks DESC LIMIT 10;";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            int rank = 1;
            while (rs.next() && rank <= 10) {
                top.add("`#" + rank + "` <@" + rs.getString("user_id") + "> - **"
                        + rs.getInt("points") + " PTS** | `" + rs.getInt("sparks") + " Sparks`");
                rank++;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return top;
    }

    public List<String> getTopBounties() {
        List<String> top = new ArrayList<>();
        String query = "SELECT user_id, bounties_cleared FROM users WHERE bounties_cleared > 0 ORDER BY bounties_cleared DESC LIMIT 10;";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            int rank = 1;
            while (rs.next() && rank <= 10) {
                top.add("`#" + rank + "` <@" + rs.getString("user_id") + "> - **"
                        + rs.getInt("bounties_cleared") + " Directives**");
                rank++;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return top;
    }

    public List<String> getTopUrgent() {
        List<String> top = new ArrayList<>();
        String query = "SELECT user_id, urgent_cleared FROM users WHERE urgent_cleared > 0 ORDER BY urgent_cleared DESC LIMIT 10;";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            int rank = 1;
            while (rs.next() && rank <= 10) {
                top.add("`#" + rank + "` <@" + rs.getString("user_id") + "> - **"
                        + rs.getInt("urgent_cleared") + " Urgent Directives**");
                rank++;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return top;
    }

    public void addShopItem(String itemName, String secretLink) {
        String query = "INSERT INTO shop_items (item_name, secret_link) VALUES (?, ?) "
                + "ON CONFLICT (item_name) DO UPDATE SET secret_link = EXCLUDED.secret_link;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, itemName);
            pstmt.setString(2, secretLink);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean shopItemExists(String itemName) {
        String query = "SELECT 1 FROM shop_items WHERE item_name = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, itemName);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public String getSecretLink(String itemName) {
        String query = "SELECT secret_link FROM shop_items WHERE item_name = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, itemName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("secret_link");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "No secure data found. Please contact a Director.";
    }

    public int getSparks(String userId) {
        String query = "SELECT sparks FROM users WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("sparks");
                }
                createNewUser(userId);
                return 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public void updateSparks(String userId, int newAmount) {
        getSparks(userId);
        String query = "UPDATE users SET sparks = ? WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setInt(1, newAmount);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getPoints(String userId) {
        String query = "SELECT points FROM users WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("points");
                }
                createNewUser(userId);
                return 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public void updatePoints(String userId, int newAmount) {
        getPoints(userId);
        String query = "UPDATE users SET points = ? WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setInt(1, newAmount);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getPity(String userId) {
        String query = "SELECT pity FROM users WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("pity");
                }
                createNewUser(userId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void updatePity(String userId, int newPity) {
        getPity(userId);
        String query = "UPDATE users SET pity = ? WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setInt(1, newPity);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getBountiesCleared(String userId) {
        String query = "SELECT bounties_cleared FROM users WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("bounties_cleared");
                }
                createNewUser(userId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int getUrgentCleared(String userId) {
        String query = "SELECT urgent_cleared FROM users WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("urgent_cleared");
                }
                createNewUser(userId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void addInventoryItem(String userId, String item) {
        String currentInventory = getInventory(userId);
        String updatedInventory = currentInventory.isEmpty() ? item : currentInventory + "," + item;
        String query = "UPDATE users SET inventory = ? WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, updatedInventory);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void updateInventory(String userId, String newInventory) {
        getInventory(userId);
        String query = "UPDATE users SET inventory = ? WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, newInventory == null ? "" : newInventory);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getInventory(String userId) {
        String query = "SELECT inventory FROM users WHERE user_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String inv = rs.getString("inventory");
                    return inv != null ? inv : "";
                }
                createNewUser(userId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "";
    }

    public void savePendingTradeSetup(String setupId, String senderId, String targetId, long expiresAt) {
        String query = "INSERT INTO pending_trade_setups "
                + "(setup_id, sender_id, target_id, selected_offer, selected_request, expires_at) "
                + "VALUES (?, ?, ?, NULL, NULL, ?) "
                + "ON CONFLICT (setup_id) DO UPDATE SET "
                + "sender_id = EXCLUDED.sender_id, target_id = EXCLUDED.target_id, expires_at = EXCLUDED.expires_at;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, setupId);
            pstmt.setString(2, senderId);
            pstmt.setString(3, targetId);
            pstmt.setLong(4, expiresAt);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public PendingTradeSetupRecord getPendingTradeSetup(String setupId) {
        String query = "SELECT * FROM pending_trade_setups WHERE setup_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, setupId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    if (expiresAt < System.currentTimeMillis()) {
                        deletePendingTradeSetup(setupId);
                        return null;
                    }
                    return new PendingTradeSetupRecord(
                            rs.getString("setup_id"),
                            rs.getString("sender_id"),
                            rs.getString("target_id"),
                            rs.getString("selected_offer"),
                            rs.getString("selected_request"),
                            expiresAt
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void updatePendingTradeOffer(String setupId, String selectedOffer) {
        String query = "UPDATE pending_trade_setups SET selected_offer = ? WHERE setup_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, selectedOffer);
            pstmt.setString(2, setupId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void updatePendingTradeRequest(String setupId, String selectedRequest) {
        String query = "UPDATE pending_trade_setups SET selected_request = ? WHERE setup_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, selectedRequest);
            pstmt.setString(2, setupId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void deletePendingTradeSetup(String setupId) {
        String query = "DELETE FROM pending_trade_setups WHERE setup_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, setupId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveActiveTrade(String tradeId, String senderId, String targetId,
                                String offerItem, String requestItem, long expiresAt) {
        String query = "INSERT INTO active_trades "
                + "(trade_id, sender_id, target_id, offer_item, request_item, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (trade_id) DO UPDATE SET "
                + "sender_id = EXCLUDED.sender_id, target_id = EXCLUDED.target_id, "
                + "offer_item = EXCLUDED.offer_item, request_item = EXCLUDED.request_item, expires_at = EXCLUDED.expires_at;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, tradeId);
            pstmt.setString(2, senderId);
            pstmt.setString(3, targetId);
            pstmt.setString(4, offerItem);
            pstmt.setString(5, requestItem);
            pstmt.setLong(6, expiresAt);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public ActiveTradeRecord getActiveTrade(String tradeId) {
        String query = "SELECT * FROM active_trades WHERE trade_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, tradeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    if (expiresAt < System.currentTimeMillis()) {
                        deleteActiveTrade(tradeId);
                        return null;
                    }
                    return new ActiveTradeRecord(
                            rs.getString("trade_id"),
                            rs.getString("sender_id"),
                            rs.getString("target_id"),
                            rs.getString("offer_item"),
                            rs.getString("request_item"),
                            expiresAt
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void deleteActiveTrade(String tradeId) {
        String query = "DELETE FROM active_trades WHERE trade_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, tradeId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void savePendingForge(String ownerId, String ingredient, long expiresAt) {
        String query = "INSERT INTO pending_forges (owner_id, ingredient, expires_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (owner_id) DO UPDATE SET ingredient = EXCLUDED.ingredient, expires_at = EXCLUDED.expires_at;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, ownerId);
            pstmt.setString(2, ingredient);
            pstmt.setLong(3, expiresAt);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getPendingForgeIngredient(String ownerId) {
        String query = "SELECT ingredient, expires_at FROM pending_forges WHERE owner_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, ownerId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    if (expiresAt < System.currentTimeMillis()) {
                        deletePendingForge(ownerId);
                        return null;
                    }
                    return rs.getString("ingredient");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void deletePendingForge(String ownerId) {
        String query = "DELETE FROM pending_forges WHERE owner_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, ownerId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private SongSuggestionRecord mapSongSuggestion(ResultSet rs) throws SQLException {
        return new SongSuggestionRecord(
                rs.getInt("song_id"),
                rs.getString("added_by"),
                rs.getString("title"),
                rs.getString("artist"),
                rs.getString("link"),
                rs.getString("source"),
                rs.getInt("is_active") == 1,
                rs.getLong("created_at"),
                rs.getLong("last_featured_at")
        );
    }

    public boolean songLinkExists(String link) {
        String query = "SELECT 1 FROM song_suggestions WHERE LOWER(link) = LOWER(?) AND is_active = 1;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, link);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public SongSuggestionRecord addSongSuggestion(String addedBy, String title, String artist, String link, String source) {
        String query = "INSERT INTO song_suggestions "
                + "(added_by, title, artist, link, source, is_active, created_at, last_featured_at) "
                + "VALUES (?, ?, ?, ?, ?, 1, ?, 0);";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, addedBy);
            pstmt.setString(2, title);
            pstmt.setString(3, artist);
            pstmt.setString(4, link);
            pstmt.setString(5, source);
            pstmt.setLong(6, System.currentTimeMillis());
            pstmt.executeUpdate();
            return getSongSuggestionByLink(link);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public int bulkAddSongSuggestions(String addedBy, List<String> links, List<String> titles, List<String> artists, String source) {
        String query = "INSERT INTO song_suggestions (added_by, title, artist, link, source, is_active, created_at, last_featured_at) VALUES (?, ?, ?, ?, ?, 1, ?, 0);";
        int count = 0;
        try {
            connection.setAutoCommit(false); 
            try (PreparedStatement pstmt = connection.prepareStatement(query)) {
                long now = System.currentTimeMillis();
                for (int i = 0; i < links.size(); i++) {
                    pstmt.setString(1, addedBy);
                    pstmt.setString(2, titles.get(i));
                    pstmt.setString(3, artists.get(i));
                    pstmt.setString(4, links.get(i));
                    pstmt.setString(5, source);
                    pstmt.setLong(6, now);
                    pstmt.addBatch(); 
                    count++;
                }
                pstmt.executeBatch(); 
                connection.commit();  
            } catch (SQLException e) {
                connection.rollback();
                e.printStackTrace();
                count = 0;
            } finally {
                connection.setAutoCommit(true); 
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return count;
    }

    public SongSuggestionRecord getSongSuggestionByLink(String link) {
        String query = "SELECT * FROM song_suggestions WHERE LOWER(link) = LOWER(?) ORDER BY song_id DESC LIMIT 1;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, link);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapSongSuggestion(rs);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public SongSuggestionRecord getSongSuggestionById(int songId) {
        String query = "SELECT * FROM song_suggestions WHERE song_id = ? LIMIT 1;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setInt(1, songId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapSongSuggestion(rs);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public List<SongSuggestionRecord> getRecentSongSuggestions(int limit) {
        List<SongSuggestionRecord> songs = new ArrayList<>();
        String query = "SELECT * FROM song_suggestions WHERE is_active = 1 ORDER BY created_at DESC LIMIT ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    songs.add(mapSongSuggestion(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return songs;
    }

    public List<SongSuggestionRecord> getSongsAddedBy(String userId, int limit) {
        List<SongSuggestionRecord> songs = new ArrayList<>();
        String query = "SELECT * FROM song_suggestions WHERE added_by = ? AND is_active = 1 ORDER BY created_at DESC LIMIT ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    songs.add(mapSongSuggestion(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return songs;
    }

    public SongSuggestionRecord getRandomActiveSongSuggestion() {
        String query = "SELECT * FROM song_suggestions WHERE is_active = 1 ORDER BY last_featured_at ASC, RANDOM() LIMIT 1;";
        try (PreparedStatement pstmt = connection.prepareStatement(query);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return mapSongSuggestion(rs);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean deactivateSongSuggestion(int songId) {
        String query = "UPDATE song_suggestions SET is_active = 0 WHERE song_id = ? AND is_active = 1;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setInt(1, songId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void markSongFeatured(int songId, long featuredAt) {
        String query = "UPDATE song_suggestions SET last_featured_at = ? WHERE song_id = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setLong(1, featuredAt);
            pstmt.setInt(2, songId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getActiveSongSuggestionCount() {
        String query = "SELECT COUNT(*) AS total FROM song_suggestions WHERE is_active = 1;";
        try (PreparedStatement pstmt = connection.prepareStatement(query);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("total");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public String getBotState(String key) {
        String query = "SELECT state_value FROM bot_state WHERE state_key = ?;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, key);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("state_value");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void setBotState(String key, String value) {
        String query = "INSERT INTO bot_state (state_key, state_value) VALUES (?, ?) "
                + "ON CONFLICT (state_key) DO UPDATE SET state_value = EXCLUDED.state_value;";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}