package com.amore;

import java.awt.Color;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs actions queued by the AMORA web dashboard (table dashboard_queue) with the same code the slash commands and
 * buttons use. The dashboard checked the staff member's rank before queueing (only its own database role can insert
 * here); this class still validates every parameter. Pending rows older than 120 s expire and never run late.
 *
 * Also publishes bot_state "dashboard_status" every minute (heartbeat + the channel ids the dashboard links to).
 */
public final class DashboardQueue {

    static final String GUILD_ID = envOr("AMORA_GUILD_ID", "1469303876720001217");
    private static final int EXPIRE_SECONDS = 120;
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");
    private static final long STARTED_AT = System.currentTimeMillis();

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "amora-dashboard-queue");
        t.setDaemon(true);
        return t;
    });

    private static AnnouncementListener announcements;

    private DashboardQueue() { }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    public static void start(JDA jda, AnnouncementListener eventListener) {
        announcements = eventListener;
        EXECUTOR.scheduleWithFixedDelay(() -> {
            try {
                tick(jda);
            } catch (Throwable t) {
                System.out.println("❌ Dashboard queue tick failed: " + t);
            }
        }, 5, 3, TimeUnit.SECONDS);
        EXECUTOR.scheduleWithFixedDelay(() -> {
            try {
                publishStatus();
            } catch (Throwable t) {
                System.out.println("❌ Dashboard status failed: " + t);
            }
        }, 0, 60, TimeUnit.SECONDS);
        System.out.println("✦ Web dashboard queue started.");
    }

    /** Wrong or impossible request; the text is shown to staff on the dashboard as-is. */
    static final class UserError extends RuntimeException {
        UserError(String message) { super(message); }
    }

    private record Job(long id, String userId, String userName, String userRank, String action, JSONObject params) { }

    // ---------------------------------------------------------------------------------------------
    // Queue
    // ---------------------------------------------------------------------------------------------

    static void tick(JDA jda) throws Exception {
        Connection conn = DatabaseManager.getInstance().ensureConnected();
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE dashboard_queue SET status = 'expired', finished_at = now(), result = ? "
                + "WHERE status = 'pending' AND created_at < now() - make_interval(secs => ?)")) {
            ps.setString(1, result(false, "Expired: AM0RA didn't get to it within 2 minutes (offline or restarting).", null).toString());
            ps.setInt(2, EXPIRE_SECONDS);
            ps.executeUpdate();
        }
        List<Job> jobs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE dashboard_queue SET status = 'running' WHERE id IN (SELECT id FROM dashboard_queue "
                + "WHERE status = 'pending' ORDER BY id LIMIT 5 FOR UPDATE SKIP LOCKED) "
                + "RETURNING id, user_id, user_name, user_rank, action, params");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject params;
                try {
                    params = new JSONObject(rs.getString("params"));
                } catch (Exception e) {
                    params = null;
                }
                jobs.add(new Job(rs.getLong("id"), rs.getString("user_id"), rs.getString("user_name"),
                        rs.getString("user_rank"), rs.getString("action"), params));
            }
        }
        for (Job job : jobs) {
            JSONObject result;
            String status;
            try {
                if (job.params() == null) throw new UserError("The request was damaged; nothing was done.");
                result = run(jda, job);
                status = "done";
            } catch (UserError | ScheduledMessages.InvalidMessage e) {
                result = result(false, e.getMessage(), null);
                status = "error";
            } catch (Exception e) {
                System.out.println("❌ Dashboard action #" + job.id() + " (" + job.action() + ") failed: " + e);
                e.printStackTrace();
                result = result(false, "Failed: " + e.getMessage(), null);
                status = "error";
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE dashboard_queue SET status = ?, result = ?, finished_at = now() WHERE id = ?")) {
                ps.setString(1, status);
                ps.setString(2, result.toString());
                ps.setLong(3, job.id());
                ps.executeUpdate();
            }
            System.out.println("✦ Dashboard action #" + job.id() + " " + job.action() + " by " + job.userName()
                    + " (" + job.userRank() + "): " + status + " | " + result.optString("message"));
        }
    }

    static JSONObject result(boolean ok, String message, JSONObject data) {
        return new JSONObject().put("ok", ok).put("message", message).put("data", data == null ? new JSONObject() : data);
    }

    private static void publishStatus() throws Exception {
        JSONObject status = new JSONObject()
                .put("version", 1)
                .put("heartbeat_ms", System.currentTimeMillis())
                .put("started_ms", STARTED_AT)
                .put("guild_id", GUILD_ID)
                .put("schedule_channel_id", envOr("SCHEDULE_CHANNEL_ID", ""))
                .put("member_schedule_channel_id", envOr("MEMBER_SCHEDULE_CHANNEL_ID", ""))
                .put("daily_song_channel_id", MusicService.DAILY_CHANNEL_ID == null ? "" : MusicService.DAILY_CHANNEL_ID)
                .put("battle_channel_id", MusicBattle.BATTLE_CHANNEL_ID == null ? "" : MusicBattle.BATTLE_CHANNEL_ID)
                .put("event_types", new JSONArray(AnnouncementListener.EVENT_TYPES));
        Connection conn = DatabaseManager.getInstance().ensureConnected();
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO bot_state (state_key, state_value) VALUES ('dashboard_status', ?) "
                + "ON CONFLICT (state_key) DO UPDATE SET state_value = EXCLUDED.state_value")) {
            ps.setString(1, status.toString());
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Parameters
    // ---------------------------------------------------------------------------------------------

    private static String text(JSONObject p, String key, int max, boolean required) {
        Object raw = p.opt(key);
        String value = raw == null || raw == JSONObject.NULL ? "" : String.valueOf(raw).trim();
        if (required && value.isEmpty()) throw new UserError("Missing " + key.replace('_', ' ') + ".");
        if (value.length() > max) throw new UserError(key.replace('_', ' ') + " is too long (max " + max + ").");
        return value;
    }

    private static String snowflake(JSONObject p, String key) {
        String value = text(p, key, 20, true);
        if (!SNOWFLAKE.matcher(value).matches()) throw new UserError(key.replace('_', ' ') + " is not a Discord id.");
        return value;
    }

    private static Guild guild(JDA jda) {
        Guild guild = jda.getGuildById(GUILD_ID);
        if (guild == null) throw new UserError("AM0RA isn't in the AMORA server right now.");
        return guild;
    }

    private static String by(Job job) {
        return "**" + (job.userName() == null ? job.userId() : job.userName()) + "** via the web dashboard";
    }

    private static JSONObject sent(Message msg, String message) {
        return result(true, message, new JSONObject().put("message_id", msg.getId()).put("jump_url", msg.getJumpUrl()));
    }

    /** An event post in either schedules channel (event_sync tells which one first). */
    private static Message eventMessage(JDA jda, String messageId) {
        String[] sync = DatabaseManager.getInstance().getEventSync(messageId);
        List<String> audiences = sync != null && "member".equals(sync[2]) ? List.of("member", "everyone") : List.of("everyone", "member");
        for (String audience : audiences) {
            MessageChannel channel = AnnouncementListener.scheduleChannel(jda, audience);
            if (channel == null) continue;
            try {
                Message msg = channel.retrieveMessageById(messageId).complete();
                if (msg.getEmbeds().isEmpty() || !msg.getAuthor().getId().equals(jda.getSelfUser().getId())) {
                    throw new UserError("That message isn't an AM0RA event post.");
                }
                return msg;
            } catch (ErrorResponseException ignored) {
                // not in this channel
            }
        }
        throw new UserError("That event post doesn't exist any more (completed and closed posts delete themselves after 2 hours).");
    }

    private static boolean isOpen(MessageEmbed embed) {
        String title = embed.getTitle();
        return title != null && !title.startsWith("✅") && !title.startsWith("🔒");
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private static JSONObject run(JDA jda, Job job) throws Exception {
        JSONObject p = job.params();
        Guild guild = guild(jda);
        switch (job.action()) {
            case "amora_msg_send": {
                GuildMessageChannel channel = ScheduledMessages.channel(jda, snowflake(p, "channel_id"));
                JSONObject message = p.optJSONObject("message");
                if (message == null) throw new UserError("Missing message.");
                Message msg = channel.sendMessage(ScheduledMessages.render(message.toString())).complete();
                return sent(msg, "Sent in #" + channel.getName() + ".");
            }
            case "amora_panel_post": {
                GuildMessageChannel channel = ScheduledMessages.channel(jda, snowflake(p, "channel_id"));
                String panel = text(p, "panel", 20, true);
                MessageCreateData data = switch (panel) {
                    case "rolepanel" -> CommandListener.rolePanel();
                    case "agepanel" -> CommandListener.agePanel();
                    case "amorahub" -> CommandListener.amoraHubPanel();
                    case "eventsetup" -> CommandListener.eventSetupPanel();
                    default -> throw new UserError("Unknown panel " + panel + ".");
                };
                Message msg = channel.sendMessage(data).complete();
                CommandListener.sendAuditLog(guild, "Panel Posted", "`" + panel + "` posted in " + channel.getAsMention() + " by " + by(job) + ".", new Color(255, 95, 162));
                return sent(msg, "Posted the " + panel + " panel in #" + channel.getName() + ".");
            }
            case "amora_event_create": return createEvent(jda, guild, job, p);
            case "amora_event_complete": {
                Message msg = eventMessage(jda, snowflake(p, "message_id"));
                MessageEmbed embed = msg.getEmbeds().get(0);
                if (!isOpen(embed)) throw new UserError("That event is already completed or closed.");
                String footer = embed.getFooter() == null || embed.getFooter().getText() == null ? "" : embed.getFooter().getText();
                java.util.regex.Matcher mTime = Pattern.compile("Time:\\s*(.+)").matcher(footer);
                if (mTime.find()) {
                    long start = AnnouncementListener.parseEventEpoch(mTime.group(1).trim());
                    if (start > 0 && System.currentTimeMillis() / 1000 < start) {
                        throw new UserError("The event hasn't started yet. Close it instead if it's cancelled.");
                    }
                }
                List<String> excluded = new ArrayList<>();
                JSONArray ex = p.optJSONArray("exclude");
                if (ex != null) {
                    for (int i = 0; i < ex.length(); i++) {
                        String id = ex.optString(i, "");
                        if (SNOWFLAKE.matcher(id).matches()) excluded.add(id);
                    }
                }
                boolean urgent = embed.getTitle().contains("🚨");
                AnnouncementListener.PayoutResult payout = announcements.payoutEvent(msg, excluded, urgent);
                if (payout.error() != null) throw new UserError(payout.error().replace("❌ ", ""));
                JSONArray paid = new JSONArray();
                int total = 0;
                for (String[] row : payout.paid()) {
                    paid.put(new JSONObject().put("user_id", row[0]).put("points", Integer.parseInt(row[1])));
                    total += Integer.parseInt(row[1]);
                }
                CommandListener.sendAuditLog(guild, "Event Paid Out", "[" + embed.getTitle() + "](" + msg.getJumpUrl() + ") completed by " + by(job)
                        + ": " + payout.paid().size() + " member(s), " + total + " Points.", new Color(50, 205, 50));
                return result(true, "Paid " + payout.paid().size() + " member(s) " + total + " Points in total.",
                        new JSONObject().put("paid", paid).put("total", total).put("jump_url", msg.getJumpUrl()));
            }
            case "amora_event_close": {
                Message msg = eventMessage(jda, snowflake(p, "message_id"));
                MessageEmbed embed = msg.getEmbeds().get(0);
                if (!isOpen(embed)) throw new UserError("That event is already completed or closed.");
                msg.editMessageEmbeds(AnnouncementListener.closedEventEmbed(embed)).setComponents(java.util.Collections.emptyList()).complete();
                AnnouncementListener.finishClosedEvent(msg);
                CommandListener.sendAuditLog(guild, "Event Closed", embed.getTitle() + " closed without payout by " + by(job) + ".", Color.DARK_GRAY);
                return result(true, "Closed " + embed.getTitle() + " without payouts (the post deletes itself in 2 hours).", null);
            }
            case "amora_event_alert": {
                Message msg = eventMessage(jda, snowflake(p, "message_id"));
                String pings = AnnouncementListener.partyPings(msg.getEmbeds().get(0));
                if (pings == null) throw new UserError("Could not read the party on that post.");
                if (pings.isEmpty()) throw new UserError("The team is currently empty.");
                msg.getChannel().sendMessage(" **MANUAL STAFF ALERT:**\n" + pings).complete();
                return result(true, "Alerted the party.", null);
            }
            case "amora_unverify": {
                String userId = snowflake(p, "user_id");
                CommandListener.unverifyMember(guild, userId);
                CommandListener.sendAuditLog(guild, "18+ Verification Reset", "<@" + userId + "> was unverified by " + by(job) + ".", Color.decode("#E67E22"));
                return result(true, "Unverified " + userId + " and removed the 18+ role.", null);
            }
            case "amora_ageban_add": {
                String userId = snowflake(p, "user_id");
                String reason = text(p, "reason", 200, false);
                CommandListener.ageBanMember(guild, userId, reason.isEmpty() ? "No reason given" : reason, job.userId(), by(job));
                return result(true, userId + " is banned from automatic 18+ verification and was unverified.", null);
            }
            case "amora_song_postnow": {
                if (MusicService.DAILY_CHANNEL_ID == null) throw new UserError("DAILY_SONG_CHANNEL_ID is not configured.");
                if (!MusicService.postDaily(jda, false)) throw new UserError("Could not post a song right now. Check the channel or make sure the pool has songs.");
                return result(true, "Bonus song drop posted.", null);
            }
            case "amora_battle_start": {
                String err = MusicBattle.start(jda);
                if (err != null) throw new UserError(err);
                return result(true, "Battle started. " + MusicBattle.status(), null);
            }
            case "amora_music_recap": {
                String raw = text(p, "month", 7, false);
                YearMonth month;
                try {
                    month = raw.isEmpty() ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(raw);
                } catch (Exception e) {
                    throw new UserError("Month must look like 2026-10.");
                }
                if (!MusicService.postRecap(jda, month)) throw new UserError("Could not post the recap. Check DAILY_SONG_CHANNEL_ID.");
                return result(true, "Recap for " + month + " posted.", null);
            }
            case "amora_forcesweep": {
                String type = text(p, "type", 10, true);
                if (type.equals("tasks")) {
                    int dropped = CommandListener.dropTestTasks(jda);
                    return result(true, "Dropped a [TEST DROP] task into " + dropped + " rank channel(s).", new JSONObject().put("dropped", dropped));
                }
                if (type.equals("sweep")) {
                    int[] counts = CommandListener.sweepDryRun(guild).get(60, TimeUnit.SECONDS);
                    return result(true, "Dry run: " + counts[1] + " of " + counts[0] + " official members have no event or task in 30 days. Nothing was changed.",
                            new JSONObject().put("checked", counts[0]).put("flagged", counts[1]));
                }
                throw new UserError("Type must be tasks or sweep.");
            }
            case "amora_testrelay": {
                String report = CommandListener.relayReport(jda, guild);
                return result(true, "Relay test finished.", new JSONObject().put("report", report));
            }
            case "amora_stats_wipe": {
                DatabaseManager.getInstance().wipeAllMonthlyStats();
                CommandListener.sendAuditLog(guild, "Monthly Stats Wiped", "All monthly trackers were wiped by " + by(job) + ".", Color.RED);
                return result(true, "All monthly trackers wiped. The server starts fresh for the new month.", null);
            }
            case "amora_shop_reset": {
                List<String> flagged = DatabaseManager.getInstance().generateCompensationReport(true);
                CommandListener.sendShopLog(guild, "Shop Counters Reset", by(job) + " performed a global shop counter wipe.", Color.RED);
                return result(true, "Shop counters reset to 0 for the new cycle.", new JSONObject().put("no_sales", new JSONArray(flagged)));
            }
            default:
                throw new UserError("Unknown action " + job.action() + ".");
        }
    }

    private static final Map<String, String> EXTRA_LABELS = Map.of(
            "game", "Games", "fashion", "Theme", "training_comp", "Theme", "training", "Server & Type");

    private static JSONObject createEvent(JDA jda, Guild guild, Job job, JSONObject p) throws Exception {
        String type = text(p, "type", 20, true);
        if (!AnnouncementListener.EVENT_TYPES.contains(type)) throw new UserError("Unknown event type " + type + ".");
        String audience = text(p, "audience", 10, true);
        if (!audience.equals("everyone") && !audience.equals("member")) throw new UserError("Audience must be everyone or member.");
        boolean urgent = p.optBoolean("urgent", false);
        if (urgent && audience.equals("everyone")) throw new UserError("Only member events can be urgent.");
        String host = text(p, "host", 100, true);
        String slots = text(p, "slots", 50, true);
        String rawTime = text(p, "time", 100, true);
        String extra = text(p, "extra", 200, false);
        if (!type.equals("movie") && extra.isEmpty()) throw new UserError("Fill in " + EXTRA_LABELS.getOrDefault(type, "Server") + ".");
        int reward;
        try {
            reward = Integer.parseInt(text(p, "reward", 7, true));
        } catch (NumberFormatException e) {
            throw new UserError("Reward must be a whole number of Points.");
        }
        if (reward < 0 || reward > 100000) throw new UserError("Reward must be between 0 and 100000 Points.");

        long epoch = AnnouncementListener.parseEventEpoch(rawTime);
        if (epoch > 0 && epoch < System.currentTimeMillis() / 1000 - 60) throw new UserError("That time is in the past.");

        if (AnnouncementListener.scheduleChannelId(audience) == null) throw new UserError("AM0RA's schedules channel isn't configured (SCHEDULE_CHANNEL_ID / MEMBER_SCHEDULE_CHANNEL_ID).");
        MessageChannel channel = AnnouncementListener.scheduleChannel(jda, audience);
        if (channel == null) throw new UserError("AM0RA can't find the schedules channel.");

        AnnouncementListener.EventPost post = announcements.buildEventPost(type, audience, urgent ? "urgent" : "standard",
                host, slots, rawTime, epoch, reward, extra, "None", 0);
        Message msg;
        String note = "";
        try {
            msg = announcements.postEvent(channel, post, audience, jda).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof AnnouncementListener.EventThreadFailed failed) {
                msg = failed.message;
                note = " The discussion thread couldn't be created.";
            } else {
                throw new UserError("Discord refused the event post: " + e.getCause().getMessage());
            }
        }
        CommandListener.sendAuditLog(guild, "Event Created", "[" + post.displayTitle() + "](" + msg.getJumpUrl() + ") created by " + by(job) + ".", new Color(255, 182, 193));
        return result(true, "Posted " + post.displayTitle() + (epoch > 0 ? "" : " (AM0RA couldn't read the time, so it shows it as text and sends no reminders)") + "." + note,
                new JSONObject().put("message_id", msg.getId()).put("jump_url", msg.getJumpUrl()));
    }
}
