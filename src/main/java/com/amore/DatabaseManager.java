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

    private static final String SQLITE_PATH = System.getenv().getOrDefault("SQLITE_PATH", "amore_gacha.db");
    private static final String URL = "jdbc:sqlite:" + SQLITE_PATH;

    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {
        connect();
        initializeDatabase();
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    private void connect() {
        try {
            connection = DriverManager.getConnection(URL);
            System.out.println("✦ SQLite Database Connected Successfully.");
        } catch (SQLException e) {
            System.out.println("❌ Failed to connect to the database.");
            e.printStackTrace();
        }
    }

    private void initializeDatabase() {
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

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createShopTable);
            System.out.println("✦ Core tables & Shop Vault verified.");
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
        String query = "INSERT OR REPLACE INTO shop_items (item_name, secret_link) VALUES (?, ?);";
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

    private void createNewUser(String userId) {
        String query = "INSERT OR IGNORE INTO users (user_id, sparks, points, inventory, pity, bounties_cleared, urgent_cleared) VALUES (?, 0, 0, '', 0, 0, 0);";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, userId);
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
}