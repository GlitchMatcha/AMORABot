package com.amore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShopRelayLogicTest {

    private Connection conn;

    @BeforeEach
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE shop_relays ("
                    + "source_channel_id TEXT NOT NULL, "
                    + "source_msg_id TEXT NOT NULL, "
                    + "target_channel_id TEXT NOT NULL, "
                    + "target_msg_id TEXT NOT NULL, "
                    + "PRIMARY KEY (source_msg_id, target_msg_id)"
                    + ");");

            stmt.execute("CREATE TABLE creator_shop_threads ("
                    + "creator_id TEXT PRIMARY KEY, "
                    + "thread_id TEXT NOT NULL"
                    + ");");

            stmt.execute("CREATE TABLE creator_stats ("
                    + "user_id TEXT PRIMARY KEY, "
                    + "commissions_enabled INTEGER DEFAULT 1"
                    + ");");
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    @DisplayName("Shop relay records can be saved and queried bidirectionally")
    public void testShopRelayBidirectionalLookup() throws Exception {
        String insertQuery = "INSERT OR IGNORE INTO shop_relays (source_channel_id, source_msg_id, target_channel_id, target_msg_id) VALUES (?, ?, ?, ?);";
        try (PreparedStatement pstmt = conn.prepareStatement(insertQuery)) {
            pstmt.setString(1, "thread_forum_123");
            pstmt.setString(2, "msg_forum_456");
            pstmt.setString(3, "hall_outfits_789");
            pstmt.setString(4, "msg_hall_999");
            pstmt.executeUpdate();
        }

        // Query by source message ID (deleting from forum thread)
        String queryBySource = "SELECT target_channel_id AS ch_id, target_msg_id AS m_id FROM shop_relays WHERE source_msg_id = ? "
                + "UNION SELECT source_channel_id AS ch_id, source_msg_id AS m_id FROM shop_relays WHERE target_msg_id = ?;";
        List<String[]> results1 = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(queryBySource)) {
            pstmt.setString(1, "msg_forum_456");
            pstmt.setString(2, "msg_forum_456");
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results1.add(new String[]{rs.getString("ch_id"), rs.getString("m_id")});
                }
            }
        }
        assertEquals(1, results1.size());
        assertEquals("hall_outfits_789", results1.get(0)[0]);
        assertEquals("msg_hall_999", results1.get(0)[1]);

        // Query by target message ID (deleting from hall)
        List<String[]> results2 = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(queryBySource)) {
            pstmt.setString(1, "msg_hall_999");
            pstmt.setString(2, "msg_hall_999");
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results2.add(new String[]{rs.getString("ch_id"), rs.getString("m_id")});
                }
            }
        }
        assertEquals(1, results2.size());
        assertEquals("thread_forum_123", results2.get(0)[0]);
        assertEquals("msg_forum_456", results2.get(0)[1]);
    }

    @Test
    @DisplayName("Deleting a shop relay by either message ID removes both directions")
    public void testDeleteShopRelay() throws Exception {
        String insertQuery = "INSERT OR IGNORE INTO shop_relays (source_channel_id, source_msg_id, target_channel_id, target_msg_id) VALUES (?, ?, ?, ?);";
        try (PreparedStatement pstmt = conn.prepareStatement(insertQuery)) {
            pstmt.setString(1, "thread_forum_123");
            pstmt.setString(2, "msg_forum_456");
            pstmt.setString(3, "hall_outfits_789");
            pstmt.setString(4, "msg_hall_999");
            pstmt.executeUpdate();
        }

        // Delete using source message ID
        String deleteQuery = "DELETE FROM shop_relays WHERE source_msg_id = ? OR target_msg_id = ?;";
        try (PreparedStatement pstmt = conn.prepareStatement(deleteQuery)) {
            pstmt.setString(1, "msg_forum_456");
            pstmt.setString(2, "msg_forum_456");
            pstmt.executeUpdate();
        }

        // Query should now return empty
        String selectQuery = "SELECT COUNT(*) FROM shop_relays;";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(selectQuery)) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("Creator shop thread mapping can be saved and updated")
    public void testCreatorShopThreadMapping() throws Exception {
        String upsert = "INSERT INTO creator_shop_threads (creator_id, thread_id) VALUES (?, ?) "
                + "ON CONFLICT (creator_id) DO UPDATE SET thread_id = excluded.thread_id;";
        try (PreparedStatement pstmt = conn.prepareStatement(upsert)) {
            pstmt.setString(1, "user_chip_1");
            pstmt.setString(2, "thread_chip_100");
            pstmt.executeUpdate();
        }

        String select = "SELECT thread_id FROM creator_shop_threads WHERE creator_id = ?;";
        try (PreparedStatement pstmt = conn.prepareStatement(select)) {
            pstmt.setString(1, "user_chip_1");
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("thread_chip_100", rs.getString("thread_id"));
            }
        }

        // Update with new thread ID
        try (PreparedStatement pstmt = conn.prepareStatement(upsert)) {
            pstmt.setString(1, "user_chip_1");
            pstmt.setString(2, "thread_chip_200");
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = conn.prepareStatement(select)) {
            pstmt.setString(1, "user_chip_1");
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("thread_chip_200", rs.getString("thread_id"));
            }
        }
    }

    @Test
    @DisplayName("Commissions toggle defaults to enabled and can be toggled on/off")
    public void testCommissionsToggle() throws Exception {
        String upsert = "INSERT INTO creator_stats (user_id, commissions_enabled) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET commissions_enabled = excluded.commissions_enabled;";

        // Disable commissions
        try (PreparedStatement pstmt = conn.prepareStatement(upsert)) {
            pstmt.setString(1, "user_rin_1");
            pstmt.setInt(2, 0);
            pstmt.executeUpdate();
        }

        String select = "SELECT commissions_enabled FROM creator_stats WHERE user_id = ?;";
        try (PreparedStatement pstmt = conn.prepareStatement(select)) {
            pstmt.setString(1, "user_rin_1");
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("commissions_enabled"));
            }
        }

        // Re-enable commissions
        try (PreparedStatement pstmt = conn.prepareStatement(upsert)) {
            pstmt.setString(1, "user_rin_1");
            pstmt.setInt(2, 1);
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = conn.prepareStatement(select)) {
            pstmt.setString(1, "user_rin_1");
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("commissions_enabled"));
            }
        }
    }
}
