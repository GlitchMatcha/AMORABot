package com.amore;

import java.awt.Color;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Messages scheduled on the AMORA web dashboard (table scheduled_messages), sent as AM0RA ✦ every 30 s.
 * Also renders the dashboard's message format (content + role pings + optional embed), which DashboardQueue uses
 * for "send now".
 *
 * Repeat rules (Vietnam time, same as dashboard/amora_tools.py next_slot):
 *   once | daily | weekly:1,3,5 (ISO Mon=1..Sun=7) | monthly:15 (day 1-28), at time_of_day "HH:MM".
 * A slot more than 6 h late (bot was offline) is skipped, never sent in a burst.
 */
public final class ScheduledMessages {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Duration MAX_LATE = Duration.ofHours(6);
    private static final Pattern REPEAT = Pattern.compile("once|daily|weekly:[1-7](,[1-7])*|monthly:([1-9]|1\\d|2[0-8])");
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "amora-scheduled-messages");
        t.setDaemon(true);
        return t;
    });

    private ScheduledMessages() { }

    public static void start(JDA jda) {
        SCHEDULER.scheduleWithFixedDelay(() -> {
            try {
                tick(jda);
            } catch (Throwable t) {
                System.out.println("❌ Scheduled messages tick failed: " + t);
            }
        }, 20, 30, TimeUnit.SECONDS);
        System.out.println("✦ Scheduled messages (web dashboard) started.");
    }

    /** A message the dashboard asked for is unusable; the text is shown to staff as-is. */
    static final class InvalidMessage extends IllegalArgumentException {
        InvalidMessage(String message) { super(message); }
    }

    // ---------------------------------------------------------------------------------------------
    // Message format: {"content": str, "role_ids": [ids], "embed": null | {title, description, color "#RRGGBB",
    //                  image_url, thumbnail_url, footer}}. Only the listed roles can be pinged.
    // ---------------------------------------------------------------------------------------------

    static MessageCreateData render(String json) {
        JSONObject m;
        try {
            m = new JSONObject(json);
        } catch (JSONException e) {
            throw new InvalidMessage("The saved message is not valid.");
        }
        List<String> roles = new ArrayList<>();
        JSONArray roleIds = m.optJSONArray("role_ids");
        if (roleIds != null) {
            for (int i = 0; i < roleIds.length(); i++) {
                String id = roleIds.optString(i, "");
                if (!SNOWFLAKE.matcher(id).matches()) throw new InvalidMessage("A pinged role id is not valid.");
                if (!roles.contains(id)) roles.add(id);
            }
        }
        String content = m.optString("content", "").trim();
        StringBuilder pings = new StringBuilder();
        for (String id : roles) {
            if (!content.contains("<@&" + id + ">")) pings.append("<@&").append(id).append("> ");
        }
        String full = (pings + content).trim();
        if (full.length() > Message.MAX_CONTENT_LENGTH) throw new InvalidMessage("The text is longer than 2000 characters.");

        MessageCreateBuilder builder = new MessageCreateBuilder();
        if (!full.isEmpty()) builder.setContent(full);

        JSONObject e = m.optJSONObject("embed");
        boolean hasEmbed = false;
        if (e != null) {
            EmbedBuilder eb = new EmbedBuilder();
            String title = e.optString("title", "").trim();
            String description = e.optString("description", "").trim();
            String footer = e.optString("footer", "").trim();
            if (title.length() > 256) throw new InvalidMessage("The embed title is longer than 256 characters.");
            if (description.length() > 4000) throw new InvalidMessage("The embed text is longer than 4000 characters.");
            if (footer.length() > 2048) throw new InvalidMessage("The embed footer is longer than 2048 characters.");
            if (!title.isEmpty()) eb.setTitle(title);
            if (!description.isEmpty()) eb.setDescription(description);
            if (!footer.isEmpty()) eb.setFooter(footer);
            String color = e.optString("color", "").trim();
            if (!color.isEmpty()) {
                if (!color.matches("#[0-9a-fA-F]{6}")) throw new InvalidMessage("The embed colour must look like #FF5FA2.");
                eb.setColor(Color.decode(color));
            }
            String image = e.optString("image_url", "").trim();
            String thumbnail = e.optString("thumbnail_url", "").trim();
            if (!image.isEmpty()) eb.setImage(httpUrl(image, "image"));
            if (!thumbnail.isEmpty()) eb.setThumbnail(httpUrl(thumbnail, "thumbnail"));
            if (!eb.isEmpty()) {
                builder.setEmbeds(eb.build());
                hasEmbed = true;
            }
        }
        if (full.isEmpty() && !hasEmbed) throw new InvalidMessage("The message is empty.");

        builder.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        builder.mentionRoles(roles);
        return builder.build();
    }

    private static String httpUrl(String url, String what) {
        if (!url.matches("https?://\\S{1,1990}")) throw new InvalidMessage("The " + what + " link must start with https://");
        return url;
    }

    /** A text / news / thread channel of the AMORA server (never another server's). */
    static GuildMessageChannel channel(JDA jda, String channelId) {
        if (channelId == null || !SNOWFLAKE.matcher(channelId).matches()) throw new InvalidMessage("Pick a channel.");
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null || !channel.getGuild().getId().equals(DashboardQueue.GUILD_ID)) {
            throw new InvalidMessage("AM0RA can't see that channel.");
        }
        if (!channel.canTalk()) throw new InvalidMessage("AM0RA isn't allowed to post in #" + channel.getName() + ".");
        return channel;
    }

    // ---------------------------------------------------------------------------------------------
    // Repeat rules
    // ---------------------------------------------------------------------------------------------

    static boolean validRepeat(String repeat) {
        return repeat != null && REPEAT.matcher(repeat).matches();
    }

    /** Earliest slot at or after `after` for a repeating rule; null for "once" or an invalid rule. */
    static ZonedDateTime nextSlot(ZonedDateTime after, String repeat, String timeOfDay) {
        if (!validRepeat(repeat) || timeOfDay == null || !TIME.matcher(timeOfDay).matches() || repeat.equals("once")) {
            return null;
        }
        ZonedDateTime from = after.withZoneSameInstant(VN);
        LocalTime time = LocalTime.parse(timeOfDay);
        if (repeat.equals("daily")) {
            ZonedDateTime slot = from.toLocalDate().atTime(time).atZone(VN);
            return slot.isBefore(from) ? slot.plusDays(1) : slot;
        }
        if (repeat.startsWith("weekly:")) {
            Set<Integer> days = new TreeSet<>();
            for (String d : repeat.substring(7).split(",")) days.add(Integer.parseInt(d));
            for (int i = 0; i <= 7; i++) {
                ZonedDateTime slot = from.toLocalDate().plusDays(i).atTime(time).atZone(VN);
                if (days.contains(slot.getDayOfWeek().getValue()) && !slot.isBefore(from)) return slot;
            }
            return null;
        }
        int day = Integer.parseInt(repeat.substring(8));
        for (int i = 0; i <= 1; i++) {
            ZonedDateTime slot = from.toLocalDate().withDayOfMonth(1).plusMonths(i).withDayOfMonth(day).atTime(time).atZone(VN);
            if (!slot.isBefore(from)) return slot;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------------

    private record Due(int id, String channelId, String message, String repeat, String timeOfDay, OffsetDateTime slot) { }

    static void tick(JDA jda) throws Exception {
        DatabaseManager db = DatabaseManager.getInstance();
        Connection conn = db.ensureConnected();
        if (conn == null) return;
        // Claim due rows (next_run_at = NULL while sending), so a second instance during a deploy can't send twice.
        List<Due> due = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "WITH due AS (SELECT id, next_run_at AS slot FROM scheduled_messages WHERE enabled AND next_run_at <= now() "
                + "ORDER BY next_run_at LIMIT 20 FOR UPDATE SKIP LOCKED) "
                + "UPDATE scheduled_messages s SET next_run_at = NULL FROM due WHERE s.id = due.id "
                + "RETURNING s.id, s.channel_id, s.message, s.repeat, s.time_of_day, due.slot");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                due.add(new Due(rs.getInt("id"), rs.getString("channel_id"), rs.getString("message"),
                        rs.getString("repeat"), rs.getString("time_of_day"), rs.getObject("slot", OffsetDateTime.class)));
            }
        }
        for (Due d : due) {
            ZonedDateTime now = ZonedDateTime.now(VN);
            ZonedDateTime slot = d.slot().atZoneSameInstant(VN);
            String error = null;
            String messageId = null;
            boolean sent = false;
            if (slot.isBefore(now.minus(MAX_LATE))) {
                error = "Skipped the " + slot.toLocalDate() + " " + slot.toLocalTime().withSecond(0).withNano(0) + " send: AM0RA was offline.";
            } else {
                try {
                    GuildMessageChannel channel = channel(jda, d.channelId());
                    messageId = channel.sendMessage(render(d.message())).complete().getId();
                    sent = true;
                } catch (InvalidMessage e) {
                    error = e.getMessage();
                } catch (Exception e) {
                    error = "Discord refused the message: " + e.getMessage();
                }
            }
            ZonedDateTime after = slot.plusMinutes(1).isAfter(now) ? slot.plusMinutes(1) : now;
            ZonedDateTime next = nextSlot(after, d.repeat(), d.timeOfDay());
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE scheduled_messages SET next_run_at = ?, enabled = ?, "
                    + "last_sent_at = CASE WHEN ? THEN now() ELSE last_sent_at END, "
                    + "last_message_id = CASE WHEN ? THEN ? ELSE last_message_id END, last_error = ? WHERE id = ?")) {
                ps.setObject(1, next == null ? null : next.toOffsetDateTime());
                ps.setBoolean(2, next != null);
                ps.setBoolean(3, sent);
                ps.setBoolean(4, sent);
                ps.setString(5, messageId);
                ps.setString(6, error);
                ps.setInt(7, d.id());
                ps.executeUpdate();
            }
            System.out.println("✦ Scheduled message #" + d.id() + (sent ? " sent" : " not sent: " + error)
                    + (next == null ? " (done)" : " · next " + next.toLocalDateTime()));
        }
    }
}
