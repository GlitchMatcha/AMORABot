package com.amore;

import java.awt.Color;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Random;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

public class CommandListener extends ListenerAdapter {

    private static final String AUDIT_LOG_CHANNEL_ID = System.getenv("AUDIT_LOG_CHANNEL_ID");
    private static final String SHOP_FORUM_CHANNEL_ID = System.getenv("SHOP_FORUM_CHANNEL_ID");
    private static final String STANDARD_BOUNTY_FORUM_ID = System.getenv("STANDARD_BOUNTY_FORUM_ID");
    private static final String URGENT_BOUNTY_FORUM_ID = System.getenv("URGENT_BOUNTY_FORUM_ID");
    private static final String ADDSPARKS_ROLE_IDS = System.getenv("ADDSPARKS_ROLE_IDS");
    private static final String PAYOUT_ROLE_IDS = System.getenv("PAYOUT_ROLE_IDS");
    private static final String DAILY_SONG_CHANNEL_ID = System.getenv("DAILY_SONG_CHANNEL_ID");

    private void sendAuditLog(Guild guild, String title, String description, Color color) {
        if (guild == null || AUDIT_LOG_CHANNEL_ID == null || AUDIT_LOG_CHANNEL_ID.isBlank()) {
            return;
        }

        TextChannel auditChannel = guild.getTextChannelById(AUDIT_LOG_CHANNEL_ID);
        if (auditChannel != null) {
            EmbedBuilder logEmbed = new EmbedBuilder()
                    .setColor(color)
                    .setTitle("📜 SYSTEM AUDIT: " + title)
                    .setDescription(description)
                    .setTimestamp(Instant.now());
            auditChannel.sendMessageEmbeds(logEmbed.build()).queue();
        }
    }

    private boolean hasAnyAllowedRole(SlashCommandInteractionEvent event, String rawRoleIds) {
        if (event.getMember() == null || rawRoleIds == null || rawRoleIds.isBlank()) {
            return false;
        }

        String[] allowedIds = rawRoleIds.split(",");
        for (String allowedId : allowedIds) {
            String trimmedId = allowedId.trim();
            if (trimmedId.isEmpty()) {
                continue;
            }

            boolean match = event.getMember().getRoles().stream()
                    .anyMatch(role -> role.getId().equals(trimmedId));

            if (match) {
                return true;
            }
        }

        return false;
    }
       
    private boolean requireAnyConfiguredRole(SlashCommandInteractionEvent event, String rawRoleIds, String envName) {
        if (event.getMember() == null) {
            event.reply("❌ This command can only be used inside a server.")
                    .setEphemeral(true).queue();
            return false;
        }

        if (rawRoleIds == null || rawRoleIds.isBlank()) {
            event.reply("❌ `" + envName + "` is not allowed to use this command wahhh T^T.")
                    .setEphemeral(true).queue();
            return false;
        }

        if (!hasAnyAllowedRole(event, rawRoleIds)) {
            event.reply("❌ You do not have any of the required roles to use this command.")
                    .setEphemeral(true).queue();
            return false;
        }

        return true;
    }

    private String getExactItemName(String inventory, String searchTerm) {
        if (inventory == null || inventory.isEmpty()) {
            return null;
        }

        for (String item : inventory.split(",")) {
            if (item.trim().equals(searchTerm.trim())) {
                return item.trim();
            }
        }
        return null;
    }
    // Add these helper methods inside CommandListener.java

private String normalizeSongLink(String raw) {
    if (raw == null) {
        return "";
    }

    String link = raw.trim();
    if (link.isBlank()) {
        return "";
    }

    try {
        URI uri = URI.create(link);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }

        if (host.equals("youtu.be")) {
            String videoId = uri.getPath() == null ? "" : uri.getPath().replace("/", "").trim();
            if (!videoId.isBlank()) {
                return "https://www.youtube.com/watch?v=" + videoId;
            }
        }

        if (host.equals("youtube.com") || host.equals("m.youtube.com") || host.equals("music.youtube.com")) {
            String videoId = getQueryParam(uri.getRawQuery(), "v");
            if (videoId != null && !videoId.isBlank()) {
                return "https://www.youtube.com/watch?v=" + videoId;
            }
        }

        if (host.equals("open.spotify.com")) {
            String path = uri.getPath() == null ? "" : uri.getPath().trim();
            if (path.startsWith("/track/")) {
                String trackId = path.substring("/track/".length());
                int slashIndex = trackId.indexOf('/');
                if (slashIndex != -1) {
                    trackId = trackId.substring(0, slashIndex);
                }
                if (!trackId.isBlank()) {
                    return "https://open.spotify.com/track/" + trackId;
                }
            }
        }
    } catch (Exception ignored) {
    }

    return link;
}

private String getQueryParam(String rawQuery, String key) {
    if (rawQuery == null || rawQuery.isBlank()) {
        return null;
    }

    for (String pair : rawQuery.split("&")) {
        int idx = pair.indexOf('=');
        if (idx <= 0) {
            continue;
        }

        String paramKey = pair.substring(0, idx);
        String paramValue = pair.substring(idx + 1);

        if (paramKey.equals(key)) {
            return URLDecoder.decode(paramValue, StandardCharsets.UTF_8);
        }
    }

    return null;
}
private String normalizeSongLink(String raw) {
    if (raw == null) {
        return "";
    }

    String link = raw.trim();
    if (link.isBlank()) {
        return "";
    }

    try {
        URI uri = URI.create(link);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }

        if (host.equals("youtu.be")) {
            String videoId = uri.getPath() == null ? "" : uri.getPath().replace("/", "").trim();
            if (!videoId.isBlank()) {
                return "https://www.youtube.com/watch?v=" + videoId;
            }
        }

        if (host.equals("youtube.com") || host.equals("m.youtube.com") || host.equals("music.youtube.com")) {
            String videoId = getQueryParam(uri.getRawQuery(), "v");
            if (videoId != null && !videoId.isBlank()) {
                return "https://www.youtube.com/watch?v=" + videoId;
            }
        }

        if (host.equals("open.spotify.com")) {
            String path = uri.getPath() == null ? "" : uri.getPath().trim();
            if (path.startsWith("/track/")) {
                String trackId = path.substring("/track/".length());
                int slashIndex = trackId.indexOf('/');
                if (slashIndex != -1) {
                    trackId = trackId.substring(0, slashIndex);
                }
                if (!trackId.isBlank()) {
                    return "https://open.spotify.com/track/" + trackId;
                }
            }
        }
    } catch (Exception ignored) {
    }

    return link;
}

private String getQueryParam(String rawQuery, String key) {
    if (rawQuery == null || rawQuery.isBlank()) {
        return null;
    }

    for (String pair : rawQuery.split("&")) {
        int idx = pair.indexOf('=');
        if (idx <= 0) {
            continue;
        }

        String paramKey = pair.substring(0, idx);
        String paramValue = pair.substring(idx + 1);

        if (paramKey.equals(key)) {
            return URLDecoder.decode(paramValue, StandardCharsets.UTF_8);
        }
    }

    return null;
}

private boolean isSupportedSongLink(String link) {
    String normalized = normalizeSongLink(link).toLowerCase(Locale.ROOT);
    return normalized.startsWith("https://open.spotify.com/track/")
            || normalized.startsWith("https://www.youtube.com/watch?v=");
}

private boolean isYouTubePlaylistLink(String link) {
    if (link == null || link.isBlank()) {
        return false;
    }

    String lower = link.toLowerCase(Locale.ROOT);
    return (lower.contains("youtube.com") || lower.contains("youtu.be"))
            && lower.contains("list=");
}

private String detectSongSource(String link) {
    String normalized = normalizeSongLink(link).toLowerCase(Locale.ROOT);
    if (normalized.contains("spotify")) {
        return "Spotify";
    }
    return "YouTube";
}

private String truncateText(String text, int maxLength) {
    if (text == null) {
        return "";
    }
    if (text.length() <= maxLength) {
        return text;
    }
    return text.substring(0, Math.max(0, maxLength - 3)) + "...";
}

private EmbedBuilder buildSongEmbed(DatabaseManager.SongSuggestionRecord song, String title, String footer) {
    return new EmbedBuilder()
            .setColor(new Color(255, 105, 180))
            .setTitle(title)
            .setDescription(
                    "🎵 **" + song.title + "**\n" +
                    "by **" + song.artist + "**\n\n" +
                    "🎧 **Listen here:**\n" + song.link + "\n\n" +
                    "🫶 **Suggested by:** <@" + song.addedBy + ">"
            )
            .addField("Source", song.source, true)
            .addField("Song ID", "#" + song.songId, true)
            .setFooter(footer, null);
}



    private String removeItem(String inventory, String exactItemName) {
        return removeMultipleItems(inventory, exactItemName, 1);
    }

    private String removeMultipleItems(String inventory, String exactItemName, int amountToRemove) {
        if (inventory == null || inventory.isEmpty()) {
            return "";
        }

        String[] itemArray = inventory.split(",");
        StringBuilder newInv = new StringBuilder();
        int removed = 0;

        for (String item : itemArray) {
            String trimmed = item.trim();
            if (removed < amountToRemove && trimmed.equals(exactItemName)) {
                removed++;
            } else if (!trimmed.isEmpty()) {
                if (newInv.length() > 0) {
                    newInv.append(",");
                }
                newInv.append(trimmed);
            }
        }

        return newInv.toString();
    }

    private int getItemCount(String inventory, String exactItemName) {
        if (inventory == null || inventory.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (String item : inventory.split(",")) {
            if (item.trim().equals(exactItemName)) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Integer> countInventory(String inventory) {
        Map<String, Integer> counts = new HashMap<>();
        if (inventory == null || inventory.isBlank()) {
            return counts;
        }

        for (String item : inventory.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                counts.put(trimmed, counts.getOrDefault(trimmed, 0) + 1);
            }
        }
        return counts;
    }

    private StringSelectMenu buildInventoryMenu(String menuId, String inventory, String placeholder) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(menuId).setPlaceholder(placeholder);

        if (inventory == null || inventory.isEmpty()) {
            menu.addOption("Empty Inventory", "empty");
            menu.setDisabled(true);
            return menu.build();
        }

        int added = 0;
        for (Map.Entry<String, Integer> entry : countInventory(inventory).entrySet()) {
            if (added >= 25) {
                break;
            }
            menu.addOption(entry.getKey() + " (x" + entry.getValue() + ")", entry.getKey());
            added++;
        }

        return menu.build();
    }

    private String readDescription(SlashCommandInteractionEvent event) {
        String description = "No description provided.";

        if (event.getOption("desc_file") != null) {
            Attachment txtFile = event.getOption("desc_file").getAsAttachment();
            try (InputStream in = txtFile.getProxy().download().join()) {
                description = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                description = "❌ Failed to read the attached .txt file.";
            }
        } else if (event.getOption("description") != null) {
            description = event.getOption("description").getAsString().replace("\\n", "\n");
        }

        if (description.length() > 4000) {
            description = description.substring(0, 4000) + "... (Truncated)";
        }

        return description;
    }

    private String encodeItem(String itemName) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(itemName.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeItem(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private int recycleValue(String itemName) {
        if (itemName.contains("3★")) {
            return 15;
        }
        if (itemName.contains("4★")) {
            return 40;
        }
        if (itemName.contains("5★")) {
            return 100;
        }
        return 20;
    }

    private List<String> forgeRewards(String ingredient) {
        List<String> rewards = new ArrayList<>();

        if (ingredient.contains("3★")) {
            rewards.add("4★ Concept Photocard & Vibrant Profile Color Role");
            rewards.add("4★ Director's Signature Pack");
            rewards.add("4★ Neon Aura Profile Theme");
        } else if (ingredient.contains("4★")) {
            rewards.add("5★ Limited Edition Custom Render Asset");
            rewards.add("5★ Premium Artist Commission Ticket");
            rewards.add("5★ Founder Relic Showcase Frame");
        }

        return rewards;
    }

    private String buildInventoryDisplay(String inventory) {
        Map<String, Integer> counts = countInventory(inventory);
        if (counts.isEmpty()) {
            return "_No items._";
        }

        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            builder.append("✦ `x").append(entry.getValue()).append("` **")
                    .append(entry.getKey()).append("**\n");
        }
        return builder.toString();
    }

    private List<String> parseMentionIds(String raw) {
        List<String> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }

        Matcher matcher = Pattern.compile("<@!?(\\d+)>").matcher(raw);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private String[] getPartyField(MessageEmbed embed) {
        for (MessageEmbed.Field field : embed.getFields()) {
            if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                return new String[]{field.getName(), field.getValue() == null ? "None" : field.getValue()};
            }
        }
        return null;
    }

    private int getPartyFieldIndex(MessageEmbed embed) {
        for (int i = 0; i < embed.getFields().size(); i++) {
            if (embed.getFields().get(i).getName() != null
                    && embed.getFields().get(i).getName().startsWith("👥 Party")) {
                return i;
            }
        }
        return -1;
    }

    private int parsePartyCurrent(String partyName) {
        return Integer.parseInt(partyName.substring(partyName.indexOf('[') + 1, partyName.indexOf('/')));
    }

    private String parsePartyMax(String partyName) {
        return partyName.substring(partyName.indexOf('/') + 1, partyName.indexOf(']'));
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload.png";
        }
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String attachImage(MessageCreateBuilder builder, Attachment attachment) throws Exception {
        if (attachment == null) {
            return null;
        }

        if (!attachment.isImage()) {
            throw new IllegalArgumentException("The uploaded file is not a valid image.");
        }

        if (attachment.getSize() > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("Image is too large. Maximum size is 8 MB.");
        }

        String safeFileName = sanitizeFileName(attachment.getFileName());
        byte[] data;

        try (InputStream in = attachment.getProxy().download().join()) {
            data = in.readAllBytes();
        }

        builder.addFiles(FileUpload.fromData(data, safeFileName));
        return "attachment://" + safeFileName;
    }

    private int getInventoryCount(String inventory) {
        if (inventory == null || inventory.isBlank()) {
            return 0;
        }

        int count = 0;
        for (String item : inventory.split(",")) {
            if (!item.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }
    private String fetchSongArtwork(String link) {
    try {
        String normalized = link.toLowerCase();

        if (normalized.contains("spotify.com/")) {
            return fetchSpotifyThumbnail(link);
        }

        if (normalized.contains("youtube.com/") || normalized.contains("youtu.be/")) {
            return fetchYouTubeThumbnail(link);
        }
    } catch (Exception e) {
        e.printStackTrace();
    }

    return null;
}

private String fetchSpotifyThumbnail(String link) {
    try {
        String encodedUrl = URLEncoder.encode(link, StandardCharsets.UTF_8);
        String endpoint = "https://open.spotify.com/oembed?url=" + encodedUrl;

        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            JSONObject json = new JSONObject(response.toString());
            return json.optString("thumbnail_url", null);
        }
    } catch (Exception e) {
        e.printStackTrace();
        return null;
    }
}

private String fetchYouTubeThumbnail(String link) {
    try {
        String encodedUrl = URLEncoder.encode(link, StandardCharsets.UTF_8);
        String endpoint = "https://www.youtube.com/oembed?format=json&url=" + encodedUrl;

        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            JSONObject json = new JSONObject(response.toString());
            return json.optString("thumbnail_url", null);
        }
    } catch (Exception e) {
        e.printStackTrace();
        return null;
    }
}
    
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getId();
        DatabaseManager db = DatabaseManager.getInstance();
        if (event.getName().equals("pull")) {
    String pullUserId = event.getUser().getId();
    final int pullCost = 50;

    List<String> ultraRarePool = Arrays.asList(
            "Celestial Bloom",
            "Velvet Halo",
            "Starlit Promise",
            "Rose Sovereign",
            "Prism Heart"
    );

    List<String> rarePool = Arrays.asList(
            "Moon Ribbon",
            "Silver Petal",
            "Neon Kiss",
            "Crystal Verse",
            "Lunar Echo",
            "Scarlet Pulse"
    );

    List<String> commonPool = Arrays.asList(
            "Soft Echo",
            "Night Polaroid",
            "Sugar Frame",
            "Paper Heart",
            "Glow Ticket",
            "Dream Static",
            "Velvet Note",
            "Cloud Sticker"
    );

    String reward;
    String rarity;
    Color embedColor;
    int remainingSparks;
    int newPity;
    int oldPity;
    boolean pityTriggered;

    synchronized (this) {
        int currentSparks = db.getSparks(pullUserId);
        int currentPity = db.getPity(pullUserId);

        if (currentSparks < pullCost) {
            event.replyEmbeds(new EmbedBuilder()
                    .setColor(Color.RED)
                    .setTitle("✦ PULL FAILED ✦")
                    .setDescription("You need **50 Sparks** to perform a pull.")
                    .addField("Current Balance", "`" + currentSparks + " Sparks`", true)
                    .addField("Required", "`50 Sparks`", true)
                    .setFooter("AMORA Gacha System", null)
                    .build()).setEphemeral(true).queue();
            return;
        }

        Random random = new Random();
        double roll = random.nextDouble();

        oldPity = currentPity;
        pityTriggered = currentPity >= 9;

        if (pityTriggered || roll < 0.05) {
            reward = ultraRarePool.get(random.nextInt(ultraRarePool.size()));
            rarity = "ULTRA RARE";
            embedColor = new Color(255, 215, 0);
            newPity = 0;
        } else if (roll < 0.25) {
            reward = rarePool.get(random.nextInt(rarePool.size()));
            rarity = "RARE";
            embedColor = new Color(186, 85, 211);
            newPity = currentPity + 1;
        } else {
            reward = commonPool.get(random.nextInt(commonPool.size()));
            rarity = "COMMON";
            embedColor = new Color(70, 130, 180);
            newPity = currentPity + 1;
        }

        db.updateSparks(pullUserId, currentSparks - pullCost);
        db.updatePity(pullUserId, newPity);
        db.addInventoryItem(pullUserId, reward);

        remainingSparks = currentSparks - pullCost;
    }

    EmbedBuilder pullEmbed = new EmbedBuilder()
            .setColor(embedColor)
            .setTitle("✦ GACHA PULL COMPLETE ✦")
            .setDescription(
                    "The AMORA signal responded to your Sparks and delivered a new reward.\n\n" +
                    "*Fate flickered. Something rare may have answered back.*"
            )
            .addField("🎁 Reward", "`" + reward + "`", false)
            .addField("🌟 Rarity", rarity, true)
            .addField("⚡ Sparks Left", "`" + remainingSparks + " Sparks`", true)
            .addField("🎯 Pity", "`" + oldPity + " → " + newPity + "`", true)
            .setFooter(pityTriggered ? "Pity activated on this pull." : "AMORA Gacha System", null);

    event.replyEmbeds(pullEmbed.build()).queue();

    sendAuditLog(event.getGuild(), "Gacha Pull",
            event.getUser().getAsMention() + " pulled **" + reward + "** [" + rarity + "] for `"
                    + pullCost + " Sparks`.",
            embedColor);
    return;
}
        if (event.getName().equals("balance")) {
            int currentSparks = db.getSparks(userId);
            int currentPoints = db.getPoints(userId);

            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(new Color(255, 105, 180))
                    .setTitle("✦ AMORA PERSONAL VAULT ✦")
                    .setDescription(
                            "A soft shimmer runs through the ledger as we check the holdings of **" + event.getUser().getName() + "**.\n\n" +
                            "⚡ **Sparks Balance**\n`" + currentSparks + "`\n\n" +
                            "💎 **Points Balance**\n`" + currentPoints + "`\n\n" +
                            "🃏 **Collection Size**\n`" + getInventoryCount(db.getInventory(userId)) + " items`\n\n" +
                            "*Your vault is always growing with every little moment of activity.*"
                    )
                    .setThumbnail(event.getUser().getEffectiveAvatarUrl())
                    .setFooter("AMORA Economy • Gentle wealth, quietly gathered", null);

            event.replyEmbeds(embed.build()).setEphemeral(true).queue();
            return;
        }

        if (event.getName().equals("profile")) {
            User targetUser = event.getOption("user") != null
                    ? event.getOption("user").getAsUser()
                    : event.getUser();

            String targetId = targetUser.getId();
            int sparks = db.getSparks(targetId);
            int points = db.getPoints(targetId);
            int pity = db.getPity(targetId);
            int collectionSize = getInventoryCount(db.getInventory(targetId));
            int directivesCleared = db.getBountiesCleared(targetId);
            int urgentCleared = db.getUrgentCleared(targetId);

            EmbedBuilder profileEmbed = new EmbedBuilder()
                    .setColor(new Color(186, 85, 211))
                    .setTitle("✦ AMORA PROFILE ✦")
                    .setDescription("An intimate snapshot of " + targetUser.getAsMention() + "'s presence in the AMORA network.")
                    .addField("⚡ Economy",
                            "**Sparks:** `" + sparks + "`\n" +
                            "**Points:** `" + points + "`\n" +
                            "**Pity:** `" + pity + "/50`",
                            false)
                    .addField("🃏 Collection",
                            "**Items Owned:** `" + collectionSize + "`",
                            false)
                    .addField("🎯 Activity",
                            "**Directives Cleared:** `" + directivesCleared + "`\n" +
                            "**Urgent Directives:** `" + urgentCleared + "`",
                            false)
                    .setThumbnail(targetUser.getEffectiveAvatarUrl())
                    .setFooter("AMORA Profile Archive", null);

            event.replyEmbeds(profileEmbed.build()).queue();
            return;
        }

        if (event.getName().equals("inventory")) {
            String rawInventory = db.getInventory(userId);

            if (rawInventory == null || rawInventory.trim().isEmpty()) {
                EmbedBuilder emptyBinder = new EmbedBuilder()
                        .setColor(new Color(75, 75, 85))
                        .setTitle("✦ DIGITAL BINDER: STILL EMPTY ✦")
                        .setDescription(
                                "Your personal collection binder has not been filled yet.\n\n" +
                                "🃏 **What belongs here:** Your pulled assets, collectibles, and curated rewards.\n" +
                                "⚡ **How to begin:** Use `/pull` to spend Sparks and claim your first item.\n\n" +
                                "*Every treasured archive begins with a single pull.*"
                        )
                        .setThumbnail(event.getUser().getEffectiveAvatarUrl())
                        .setFooter("AMORA Collection Binder • Waiting for its first memory", null);

                event.replyEmbeds(emptyBinder.build()).queue();
                return;
            }

            EmbedBuilder invEmbed = new EmbedBuilder()
                    .setColor(new Color(255, 105, 180))
                    .setTitle("✦ CURATED COLLECTION BINDER ✦")
                    .setDescription(
                            "Viewing the private archive of " + event.getUser().getAsMention() + ".\n\n" +
                            buildInventoryDisplay(rawInventory) +
                            "\n*Every entry here is a small piece of your AMORA story.*"
                    )
                    .setThumbnail(event.getUser().getEffectiveAvatarUrl())
                    .setFooter("AMORA Binder • Carefully preserved", null);

            event.replyEmbeds(invEmbed.build()).queue();
            return;
        }

        if (event.getName().equals("forge")) {
            String inv = db.getInventory(userId);
            if (inv == null || inv.isEmpty()) {
                event.reply("❌ Your inventory is empty! You have nothing to forge or recycle.")
                        .setEphemeral(true).queue();
                return;
            }

            StringSelectMenu.Builder craftMenu = StringSelectMenu.create("forge_craft_" + userId)
                    .setPlaceholder("🔨 Select 3x duplicates to begin crafting...");
            boolean canCraft = false;

            if (getItemCount(inv, "3★ Standard Server Photocard Bundle") >= 3) {
                craftMenu.addOption("Burn 3x: 3★ Bundle", "3★ Standard Server Photocard Bundle");
                canCraft = true;
            }
            if (getItemCount(inv, "4★ Concept Photocard & Vibrant Profile Color Role") >= 3) {
                craftMenu.addOption("Burn 3x: 4★ Concept Role", "4★ Concept Photocard & Vibrant Profile Color Role");
                canCraft = true;
            }
            if (!canCraft) {
                craftMenu.addOption("Not enough 3★ or 4★ duplicates to craft.", "none");
                craftMenu.setDisabled(true);
            }

            StringSelectMenu.Builder recycleMenu = StringSelectMenu.create("forge_recycle_" + userId)
                    .setPlaceholder("♻️ Select an item to recycle for Sparks...");
            int added = 0;
            for (Map.Entry<String, Integer> entry : countInventory(inv).entrySet()) {
                if (added >= 25) {
                    break;
                }
                int val = recycleValue(entry.getKey());
                recycleMenu.addOption(entry.getKey() + " (x" + entry.getValue() + ") -> +" + val + " Sparks", entry.getKey());
                added++;
            }

            EmbedBuilder forgeEmbed = new EmbedBuilder()
                    .setColor(new Color(255, 140, 0))
                    .setTitle("✦ THE SYNTHESIS FORGE ✦")
                    .setDescription("Welcome to the Forge, " + event.getUser().getAsMention() + ".\n\n"
                            + "🔨 **Crafting:** Burn `3` identical Gacha drops to choose a reward of the next tier up.\n"
                            + "♻️ **Recycling:** Melt down any unwanted item to instantly recover Sparks.")
                    .setFooter("Warning: Forge actions are permanent and cannot be undone.", null);

            event.replyEmbeds(forgeEmbed.build())
                    .addActionRow(craftMenu.build())
                    .addActionRow(recycleMenu.build())
                    .queue();
            return;
        }

        if (event.getName().equals("leaderboard")) {
            String category = event.getOption("category").getAsString();
            List<String> topList = new ArrayList<>();
            String title = "";
            Color color = Color.WHITE;

            if (category.equals("wealth")) {
                title = "💰 SERVER WEALTH RANKINGS";
                color = new Color(255, 215, 0);
                topList = db.getTopWealth();
            } else if (category.equals("bounties")) {
                title = "🎯 TOP DIRECTIVE HUNTERS";
                color = new Color(0, 250, 154);
                topList = db.getTopBounties();
            } else if (category.equals("urgent")) {
                title = "🚨 TOP URGENT RESPONDERS";
                color = new Color(255, 69, 0);
                topList = db.getTopUrgent();
            }

            StringBuilder desc = new StringBuilder();
            if (topList.isEmpty()) {
                desc.append("_No data available yet!_");
            } else {
                for (String entry : topList) {
                    desc.append(entry).append("\n\n");
                }
            }

            event.replyEmbeds(new EmbedBuilder()
                    .setColor(color)
                    .setTitle(title)
                    .setDescription(desc.toString())
                    .setFooter("AMORA Network Rankings", null)
                    .build()).queue();
            return;
        }

        if (event.getName().equals("trade")) {
            User targetUser = event.getOption("target").getAsUser();
            String senderId = event.getUser().getId();
            String targetId = targetUser.getId();

            if (targetUser.isBot() || senderId.equals(targetId)) {
                event.reply("❌ Invalid target.").setEphemeral(true).queue();
                return;
            }

            String senderInv = db.getInventory(senderId);
            String targetInv = db.getInventory(targetId);

            if (senderInv == null || senderInv.isEmpty()) {
                event.reply("❌ You don't have assets!").setEphemeral(true).queue();
                return;
            }
            if (targetInv == null || targetInv.isEmpty()) {
                event.reply("❌ They don't have assets!").setEphemeral(true).queue();
                return;
            }

            String setupId = UUID.randomUUID().toString().substring(0, 8);
            long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
            db.savePendingTradeSetup(setupId, senderId, targetId, expiresAt);

            event.replyEmbeds(new EmbedBuilder()
                            .setColor(new Color(138, 43, 226))
                            .setTitle("✦ TRADE CONFIGURATION ✦")
                            .setDescription("Build trade proposal with " + targetUser.getAsMention() + ".")
                            .build())
                    .addActionRow(buildInventoryMenu("offer_" + setupId, senderInv, "📤 Select item to give..."))
                    .addActionRow(buildInventoryMenu("req_" + setupId, targetInv, "📥 Select item you want..."))
                    .addActionRow(
                            Button.success("propose_" + setupId, "🚀 Send Proposal"),
                            Button.danger("cancelsetup_" + setupId, "Cancel"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (event.getName().equals("publish")) {
            if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("❌ No clearance.").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("forum").getAsChannel().getType() != ChannelType.FORUM) {
                event.reply("❌ MUST be a Forum Channel!").setEphemeral(true).queue();
                return;
            }
            if (SHOP_FORUM_CHANNEL_ID == null || SHOP_FORUM_CHANNEL_ID.isBlank()) {
                event.reply("❌ SHOP_FORUM_CHANNEL_ID is not configured.").setEphemeral(true).queue();
                return;
            }
            if (!event.getOption("forum").getAsChannel().getId().equals(SHOP_FORUM_CHANNEL_ID)) {
                event.reply("❌ **Access Denied:** Wrong channel!").setEphemeral(true).queue();
                return;
            }

            String itemName = event.getOption("name").getAsString();
            String safeName = itemName.length() > 60 ? itemName.substring(0, 60) : itemName;

            if (db.shopItemExists(safeName)) {
                event.reply("❌ Upload Aborted: This item already exists in the shop.").setEphemeral(true).queue();
                return;
            }

            event.deferReply(true).queue();
            ForumChannel forum = event.getOption("forum").getAsChannel().asForumChannel();
            int price = event.getOption("price").getAsInt();
            String secretDelivery = event.getOption("delivery").getAsString();
            String description = readDescription(event);
            String encodedItem = encodeItem(safeName);
            String buttonId = "buy_" + price + "_" + encodedItem;

            MessageCreateBuilder builder = new MessageCreateBuilder();
            List<MessageEmbed> embeds = new ArrayList<>();

            EmbedBuilder mainEmbed = new EmbedBuilder()
                    .setColor(new Color(0, 250, 154))
                    .setTitle(itemName.toUpperCase())
                    .setDescription(description + "\n\n💰 **Cost:** `" + price + " Points`");

            try {
                String image1 = null;
                String image2 = null;

                if (event.getOption("file1") != null) {
                    image1 = attachImage(builder, event.getOption("file1").getAsAttachment());
                } else if (event.getOption("url1") != null) {
                    String url1 = event.getOption("url1").getAsString();
                    if (url1 != null && !url1.isBlank()) {
                        image1 = url1;
                    }
                }

                if (event.getOption("file2") != null) {
                    image2 = attachImage(builder, event.getOption("file2").getAsAttachment());
                } else if (event.getOption("url2") != null) {
                    String url2 = event.getOption("url2").getAsString();
                    if (url2 != null && !url2.isBlank()) {
                        image2 = url2;
                    }
                }

                if (image1 != null) {
                    mainEmbed.setImage(image1);
                }

                embeds.add(mainEmbed.build());

                if (image2 != null) {
                    embeds.add(new EmbedBuilder()
                            .setColor(new Color(0, 250, 154))
                            .setImage(image2)
                            .build());
                }
            } catch (Exception e) {
                event.getHook().sendMessage("❌ Failed to process uploaded files: " + e.getMessage()).queue();
                return;
            }

            builder.addEmbeds(embeds);
            builder.addActionRow(Button.success(buttonId, "Purchase • " + price + " PTS"));

            forum.createForumPost(itemName, builder.build()).queue(
                    success -> {
                        db.addShopItem(safeName, secretDelivery);
                        event.getHook().sendMessage("✅ Asset published!").queue();
                        sendAuditLog(event.getGuild(), "Asset Published",
                                event.getUser().getAsMention() + " published **" + safeName + "** to the shop for `"
                                        + price + " Points`.",
                                new Color(0, 250, 154));
                    },
                    error -> {
                        error.printStackTrace();
                        event.getHook().sendMessage(
                                        "⚠️ Discord returned an upload/network error after submission. Check the forum — the shop post may already exist.")
                                .queue();
                    }
            );
            return;
        }

        if (event.getName().equals("bounty")) {
            if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("❌ You do not have clearance to manage Bounties.").setEphemeral(true).queue();
                return;
            }

            String subCommand = event.getSubcommandName();

            if ("post".equals(subCommand)) {
                if (event.getOption("forum").getAsChannel().getType() != ChannelType.FORUM) {
                    event.reply("❌ MUST be a Forum Channel!").setEphemeral(true).queue();
                    return;
                }
                if (STANDARD_BOUNTY_FORUM_ID == null || URGENT_BOUNTY_FORUM_ID == null) {
                    event.reply("❌ Bounty forum IDs are not configured.").setEphemeral(true).queue();
                    return;
                }

                String selectedForumId = event.getOption("forum").getAsChannel().getId();
                if (!selectedForumId.equals(STANDARD_BOUNTY_FORUM_ID) && !selectedForumId.equals(URGENT_BOUNTY_FORUM_ID)) {
                    event.reply("❌ **Access Denied:** Must be an official Quest Forum!").setEphemeral(true).queue();
                    return;
                }

                ForumChannel forum = event.getOption("forum").getAsChannel().asForumChannel();
                String title = event.getOption("title").getAsString();
                int reward = event.getOption("reward").getAsInt();
                String slotsInput = event.getOption("slots").getAsString();
                int maxSlots = 0;
                try {
                    maxSlots = Integer.parseInt(slotsInput.trim());
                } catch (NumberFormatException ignored) {
                    maxSlots = 0;
                }

                String slotDisplay = maxSlots <= 0 ? "Unlimited" : String.valueOf(maxSlots);
                String description = readDescription(event);

                event.deferReply(true).queue();

                MessageCreateBuilder builder = new MessageCreateBuilder();
                EmbedBuilder questEmbed = new EmbedBuilder()
                        .setColor(new Color(255, 69, 0))
                        .setTitle("🎯 DIRECTIVE: " + title.toUpperCase())
                        .setDescription(description + "\n\n💰 **Bounty Reward:** `" + reward + " Points` _(Per Person)_\n\n_Press Join below to enter the party!_")
                        .addField("👥 Party [0/" + slotDisplay + "]", "None", false)
                        .setFooter("AMORA Directive Network • Reward embedded: " + reward, null);

                try {
                    if (event.getOption("image") != null) {
                        String imageRef = attachImage(builder, event.getOption("image").getAsAttachment());
                        questEmbed.setImage(imageRef);
                    }
                } catch (Exception e) {
                    event.getHook().sendMessage("❌ Failed to process uploaded image: " + e.getMessage()).queue();
                    return;
                }

                builder.addEmbeds(questEmbed.build());
                builder.addActionRow(
                        Button.success("bjoin_button", "✋ Join Quest"),
                        Button.danger("bleave_button", "🛑 Leave Quest")
                );

                forum.createForumPost("🎯 " + title + " [" + reward + " PTS]", builder.build()).queue(
                        success -> {
                            event.getHook().sendMessage("✅ Dynamic Party Bounty posted!").queue();
                            sendAuditLog(event.getGuild(), "Bounty Posted",
                                    event.getUser().getAsMention() + " posted Directive: **" + title + "** for `"
                                            + reward + " Points`.", new Color(255, 69, 0));
                        },
                        error -> {
                            error.printStackTrace();
                            event.getHook().sendMessage(
                                            "⚠️ Discord returned an upload/network error after submission. Check the quest forum — the post may already exist.")
                                    .queue();
                        }
                );
                return;
            }

            if ("kick".equals(subCommand)) {
                if (!event.getChannel().getType().isThread()) {
                    event.reply("❌ Run this inside the Quest Thread!").setEphemeral(true).queue();
                    return;
                }

                User target = event.getOption("target").getAsUser();
                ThreadChannel thread = event.getChannel().asThreadChannel();

                event.reply("🔄 Removing " + target.getName() + " from the party...").setEphemeral(true).queue(replyHook ->
                        thread.retrieveStartMessage().queue(startMsg -> {
                            MessageEmbed oldEmbed = startMsg.getEmbeds().get(0);
                            EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);
                            String[] partyField = getPartyField(oldEmbed);
                            int fieldIndex = getPartyFieldIndex(oldEmbed);

                            if (partyField == null || fieldIndex == -1) {
                                replyHook.editOriginal("❌ Party data not found.").queue();
                                return;
                            }

                            String partyName = partyField[0];
                            String partyValue = partyField[1];
                            String userMention = target.getAsMention();

                            if (!partyValue.contains(userMention)) {
                                replyHook.editOriginal("❌ " + userMention + " is not currently in the party!").queue();
                                return;
                            }

                            int current = parsePartyCurrent(partyName);
                            String maxStr = parsePartyMax(partyName);

                            partyValue = partyValue.replace(userMention + "\n", "")
                                    .replace("\n" + userMention, "")
                                    .replace(userMention, "");

                            if (partyValue.trim().isEmpty()) {
                                partyValue = "None";
                            }

                            current--;

                            newEmbed.getFields().remove(fieldIndex);
                            newEmbed.addField("👥 Party [" + current + "/" + maxStr + "]", partyValue, false);

                            if (current == 0) {
                                newEmbed.setColor(new Color(255, 69, 0));
                            }

                            startMsg.editMessageEmbeds(newEmbed.build())
                                    .setActionRow(
                                            Button.success("bjoin_button", "✋ Join Quest"),
                                            Button.danger("bleave_button", "🛑 Leave Quest")
                                    )
                                    .queue();

                            replyHook.editOriginal("✅ Successfully kicked " + userMention + " from the party.").queue();
                            thread.sendMessage("⚠️ Admin Action: " + userMention + " has been removed from the party by a Director.").queue();
                            sendAuditLog(event.getGuild(), "Bounty Kick",
                                    event.getUser().getAsMention() + " removed " + userMention + " from a party in thread `"
                                            + thread.getId() + "`.", Color.ORANGE);
                        }, error -> replyHook.editOriginal("❌ Error fetching the starting message.").queue()));
                return;
            }

            if ("cancel".equals(subCommand)) {
                if (!event.getChannel().getType().isThread()) {
                    event.reply("❌ Run this inside the Quest Thread!").setEphemeral(true).queue();
                    return;
                }

                ThreadChannel thread = event.getChannel().asThreadChannel();
                event.reply("🔄 Aborting directive...").queue(replyHook ->
                        thread.retrieveStartMessage().queue(startMsg -> {
                            MessageEmbed oldEmbed = startMsg.getEmbeds().get(0);
                            EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);
                            newEmbed.setColor(Color.RED);

                            int partyFieldIndex = getPartyFieldIndex(oldEmbed);
                            if (partyFieldIndex != -1) {
                                newEmbed.getFields().remove(partyFieldIndex);
                            }

                            newEmbed.addField("❌ DIRECTIVE CANCELLED",
                                    "This quest was aborted by " + event.getUser().getAsMention() + ". No points were awarded.", false);

                            startMsg.editMessageEmbeds(newEmbed.build()).setComponents().queue(done -> {
                                replyHook.editOriginalEmbeds(new EmbedBuilder()
                                        .setColor(Color.RED)
                                        .setTitle("DIRECTIVE ABORTED")
                                        .setDescription("Quest cancelled and locked.")
                                        .build()).setContent("").queue(done2 ->
                                        thread.getManager().setLocked(true).setArchived(true).queue());

                                sendAuditLog(event.getGuild(), "Bounty Cancelled",
                                        event.getUser().getAsMention() + " aborted the quest in thread `"
                                                + thread.getId() + "`.", Color.RED);
                            });
                        }, error -> replyHook.editOriginal("❌ Error fetching the starting message.").queue()));
                return;
            }

            if ("complete".equals(subCommand)) {
                if (!event.getChannel().getType().isThread()) {
                    event.reply("❌ Run this inside the Quest Thread!").setEphemeral(true).queue();
                    return;
                }

                ThreadChannel thread = event.getChannel().asThreadChannel();
                boolean isUrgent = URGENT_BOUNTY_FORUM_ID != null
                        && thread.getParentChannel().getId().equals(URGENT_BOUNTY_FORUM_ID);
                String commandExecutorId = event.getUser().getId();

                event.reply("🔄 Processing party mass-payout and logging stats...").queue(replyHook ->
                        thread.retrieveStartMessage().queue(startMsg -> {
                            MessageEmbed oldEmbed = startMsg.getEmbeds().get(0);
                            String footerText = oldEmbed.getFooter() != null ? oldEmbed.getFooter().getText() : "0";
                            int rewardAmount = Integer.parseInt(footerText.replaceAll("[^0-9]", ""));
                            String[] partyField = getPartyField(oldEmbed);
                            int partyFieldIndex = getPartyFieldIndex(oldEmbed);
                            String partyData = partyField == null ? "None" : partyField[1];

                            if (partyData.equals("None") || partyData.isEmpty()) {
                                replyHook.editOriginal("❌ Cannot complete. The party is empty!").queue();
                                return;
                            }

                            List<String> excludedIds = parseMentionIds(event.getOption("exclude") != null
                                    ? event.getOption("exclude").getAsString() : "");
                            List<String> userIds = parseMentionIds(partyData);
                            boolean selfApprove = userIds.contains(commandExecutorId) && !excludedIds.contains(commandExecutorId);
                            StringBuilder payoutLog = new StringBuilder();

                            for (String uid : userIds) {
                                if (excludedIds.contains(uid)) {
                                    payoutLog.append("• <@").append(uid).append("> was excluded from the payout.\n");
                                    continue;
                                }

                                int currentPoints = db.getPoints(uid);
                                db.updatePoints(uid, currentPoints + rewardAmount);
                                db.incrementBountyStats(uid, isUrgent);
                                payoutLog.append("• <@").append(uid).append("> received `")
                                        .append(rewardAmount).append(" PTS`\n");
                            }

                            EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);
                            newEmbed.setColor(selfApprove ? Color.ORANGE : Color.GREEN);

                            if (partyFieldIndex != -1) {
                                newEmbed.getFields().remove(partyFieldIndex);
                            }

                            newEmbed.addField("✅ QUEST CLEARED",
                                    "Successfully completed by the party!\n\n" + payoutLog, false);

                            if (selfApprove) {
                                newEmbed.addField("⚠️ OVERRIDE LOGGED",
                                        event.getUser().getAsMention() + " authorized a payout that included themselves.", false);
                            }

                            startMsg.editMessageEmbeds(newEmbed.build()).setComponents().queue(done -> {
                                EmbedBuilder receiptEmbed = new EmbedBuilder()
                                        .setColor(selfApprove ? Color.ORANGE : Color.GREEN)
                                        .setTitle(selfApprove ? "DIRECTIVE CLEARED WITH WARNING" : "DIRECTIVE CLEARED")
                                        .setDescription("All party members have been paid and stats updated. Thread locking and archiving...");

                                replyHook.editOriginalEmbeds(receiptEmbed.build()).setContent("").queue(done2 ->
                                        thread.getManager().setLocked(true).setArchived(true).queue());

                                if (selfApprove) {
                                    sendAuditLog(event.getGuild(), "SUSPICIOUS PAYOUT",
                                            event.getUser().getAsMention() + " self-approved a bounty payout in thread `"
                                                    + thread.getId() + "` for `" + rewardAmount + " Points`.", Color.RED);
                                } else {
                                    sendAuditLog(event.getGuild(), "Bounty Cleared",
                                            event.getUser().getAsMention() + " cleared the quest in thread `"
                                                    + thread.getId() + "`. Paid out `" + rewardAmount + " Points`.",
                                            Color.GREEN);
                                }
                            });
                        }, error -> replyHook.editOriginal("❌ Error fetching the starting message.").queue()));
                return;
            }

            return;
        }

        if (event.getName().equals("addsparks")) {
            if (!requireAnyConfiguredRole(event, ADDSPARKS_ROLE_IDS, "ADDSPARKS_ROLE_IDS")) {
                return;
            }

            User targetUser = event.getOption("target").getAsUser();
            int amount = event.getOption("amount").getAsInt();

            if (amount <= 0) {
                event.reply("❌ Amount must be greater than 0.")
                        .setEphemeral(true).queue();
                return;
            }

            int currentTargetSparks = db.getSparks(targetUser.getId());
            db.updateSparks(targetUser.getId(), currentTargetSparks + amount);

            event.replyEmbeds(new EmbedBuilder()
                    .setColor(Color.GREEN)
                    .setTitle("VAULT UPDATED")
                    .setDescription("Minted **" + amount + " Sparks** for " + targetUser.getAsMention() + ".")
                    .addField("Updated Balance", "`" + (currentTargetSparks + amount) + " Sparks`", false)
                    .build()).queue();

            sendAuditLog(event.getGuild(), "Sparks Minted",
                    event.getUser().getAsMention() + " minted **" + amount + " Sparks** to "
                            + targetUser.getAsMention() + ".", Color.ORANGE);
            return;
        }

        if (event.getName().equals("payout")) {
            if (!requireAnyConfiguredRole(event, PAYOUT_ROLE_IDS, "PAYOUT_ROLE_IDS")) {
                return;
            }

            User targetUser = event.getOption("target").getAsUser();
            int amount = event.getOption("amount").getAsInt();
            String reason = event.getOption("reason").getAsString();

            if (amount <= 0) {
                event.reply("❌ Amount must be greater than 0.")
                        .setEphemeral(true).queue();
                return;
            }

            int currentTargetPoints = db.getPoints(targetUser.getId());
            db.updatePoints(targetUser.getId(), currentTargetPoints + amount);

            EmbedBuilder payoutEmbed = new EmbedBuilder()
                    .setColor(new Color(0, 250, 154))
                    .setTitle("✦ BOUNTY PAYOUT CLEARED ✦")
                    .setDescription("A reward has been delivered successfully through the AMORA network.\n"
                            + "Thank you for making meaningful work worth celebrating.")
                    .addField("Recipient", targetUser.getAsMention(), true)
                    .addField("Points Granted", "`" + amount + " Points`", true)
                    .addField("Reason", reason, false)
                    .addField("Updated Total", "`" + (currentTargetPoints + amount) + " Points`", false)
                    .setThumbnail(targetUser.getEffectiveAvatarUrl())
                    .setFooter("AMORA Directive Ledger", null);

            event.replyEmbeds(payoutEmbed.build()).queue();

            sendAuditLog(event.getGuild(), "Manual Payout",
                    event.getUser().getAsMention() + " paid " + targetUser.getAsMention()
                            + " **" + amount + " Points** for `" + reason + "`.",
                    new Color(0, 250, 154));
            return;
        }

        if (event.getName().equals("award")) {
            if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("❌ You do not have clearance to award performance Sparks.").setEphemeral(true).queue();
                return;
            }

            User targetUser = event.getOption("target").getAsUser();
            int amount = event.getOption("amount").getAsInt();
            String reason = event.getOption("reason").getAsString();
            int currentTargetSparks = db.getSparks(targetUser.getId());
            db.updateSparks(targetUser.getId(), currentTargetSparks + amount);

            EmbedBuilder awardEmbed = new EmbedBuilder()
                    .setColor(new Color(255, 215, 0))
                    .setTitle("STAGE CLEAR REWARD ISSUED")
                    .setDescription("Massive energy detected. " + targetUser.getAsMention()
                            + " has been awarded for their impact.\n\nAchievement: **" + reason
                            + "**\nBounty Claimed: `" + amount + " Sparks`")
                    .setThumbnail(targetUser.getEffectiveAvatarUrl())
                    .setFooter("AMORA Performance Ecosystem", null);

            event.replyEmbeds(awardEmbed.build()).queue();
            sendAuditLog(event.getGuild(), "Performance Award",
                    event.getUser().getAsMention() + " awarded " + targetUser.getAsMention() + " `"
                            + amount + " Sparks` for **" + reason + "**.",
                    new Color(255, 215, 0));
        }
                       if (event.getName().equals("song")) {
            String subcommand = event.getSubcommandName();

            if (subcommand == null) {
                event.reply("❌ Missing subcommand. Use `/song add`, `/song importplaylist`, `/song list`, `/song suggest`, or `/song remove`.")
                        .setEphemeral(true).queue();
                return;
            }

            if (subcommand.equals("add")) {
                String title = event.getOption("title").getAsString().trim();
                String artist = event.getOption("artist").getAsString().trim();
                String link = normalizeSongLink(event.getOption("link").getAsString().trim());

                if (title.isBlank() || artist.isBlank() || link.isBlank()) {
                    event.reply("❌ Title, artist, and link are required.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (title.length() > 120 || artist.length() > 120 || link.length() > 500) {
                    event.reply("❌ One or more fields are too long.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (!isSupportedSongLink(link)) {
                    event.reply("❌ Please submit a valid Spotify track or YouTube song link.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (db.songLinkExists(link)) {
                    event.reply("❌ That exact song link is already in the AMORA pool.")
                            .setEphemeral(true).queue();
                    return;
                }

                DatabaseManager.SongSuggestionRecord created = db.addSongSuggestion(
                        userId,
                        title,
                        artist,
                        link,
                        detectSongSource(link)
                );

                if (created == null) {
                    event.reply("❌ Failed to save the song suggestion.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.replyEmbeds(
                        buildSongEmbed(created, "✦ SONG ADDED TO THE AMORA POOL ✦", "AMORA Daily Music Pool").build()
                ).setEphemeral(true).queue();
                return;
            }

            if (subcommand.equals("importplaylist")) {
                String playlistLink = event.getOption("link").getAsString().trim();

                if (playlistLink.isBlank()) {
                    event.reply("❌ Playlist link is required.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (!isYouTubePlaylistLink(playlistLink)) {
                    event.reply("❌ Please provide a valid public YouTube playlist link.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.deferReply(true).queue();

                try {
                    List<YouTubePlaylistImporter.ImportedSong> importedSongs =
                            YouTubePlaylistImporter.importPlaylist(playlistLink);

                    if (importedSongs.isEmpty()) {
                        event.getHook().sendMessage("❌ No usable songs were found in that playlist.")
                                .queue();
                        return;
                    }

                    int added = 0;
                    int skippedExisting = 0;
                    int skippedInvalid = 0;

                    Set<String> seenThisImport = new HashSet<>();
                    StringBuilder preview = new StringBuilder();

                    for (YouTubePlaylistImporter.ImportedSong imported : importedSongs) {
                        String normalizedLink = normalizeSongLink(imported.link());

                        if (normalizedLink.isBlank() || !isSupportedSongLink(normalizedLink)) {
                            skippedInvalid++;
                            continue;
                        }

                        String dedupeKey = normalizedLink.toLowerCase(Locale.ROOT);
                        if (!seenThisImport.add(dedupeKey)) {
                            skippedInvalid++;
                            continue;
                        }

                        if (db.songLinkExists(normalizedLink)) {
                            skippedExisting++;
                            continue;
                        }

                        DatabaseManager.SongSuggestionRecord created = db.addSongSuggestion(
                                userId,
                                imported.title(),
                                imported.artist(),
                                normalizedLink,
                                "YouTube"
                        );

                        if (created == null) {
                            skippedInvalid++;
                            continue;
                        }

                        added++;

                        if (preview.length() < 900) {
                            preview.append("`#").append(created.songId).append("` ")
                                    .append("**").append(truncateText(created.title, 40)).append("**")
                                    .append(" — ").append(truncateText(created.artist, 30))
                                    .append("\n");
                        }
                    }

                    EmbedBuilder resultEmbed = new EmbedBuilder()
                            .setColor(new Color(255, 105, 180))
                            .setTitle("✦ PLAYLIST IMPORT COMPLETE ✦")
                            .setDescription(
                                    "🎵 Playlist scan finished.\n\n" +
                                    "✅ Added: `" + added + "`\n" +
                                    "♻️ Already in pool: `" + skippedExisting + "`\n" +
                                    "⚠️ Skipped/invalid: `" + skippedInvalid + "`"
                            )
                            .setFooter("AMORA Music Importer", null);

                    if (preview.length() > 0) {
                        resultEmbed.addField("Imported Songs", preview.toString(), false);
                    }

                    event.getHook().sendMessageEmbeds(resultEmbed.build()).queue();

                } catch (Exception e) {
                    e.printStackTrace();
                    event.getHook().sendMessage("❌ Failed to import playlist: " + e.getMessage()).queue();
                }
                return;
            }

            if (subcommand.equals("remove")) {
                int songId = event.getOption("id").getAsInt();
                DatabaseManager.SongSuggestionRecord song = db.getSongSuggestionById(songId);

                if (song == null || !song.active) {
                    event.reply("❌ That song ID does not exist in the active pool.")
                            .setEphemeral(true).queue();
                    return;
                }

                boolean isOwner = userId.equals(song.addedBy);
                boolean isAdmin = event.getMember() != null
                        && event.getMember().hasPermission(Permission.ADMINISTRATOR);

                if (!isOwner && !isAdmin) {
                    event.reply("❌ You can only remove songs you added yourself unless you are an admin.")
                            .setEphemeral(true).queue();
                    return;
                }

                boolean removed = db.deactivateSongSuggestion(songId);
                if (!removed) {
                    event.reply("❌ Failed to remove that song.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.reply("✅ Removed **" + song.title + "** by **" + song.artist + "** from the AMORA pool.")
                        .setEphemeral(true).queue();
                return;
            }

            if (subcommand.equals("list")) {
                List<DatabaseManager.SongSuggestionRecord> songs = db.getRecentSongSuggestions(10);

                if (songs.isEmpty()) {
                    event.reply("❌ The AMORA song pool is empty right now. Add one with `/song add`.")
                            .setEphemeral(true).queue();
                    return;
                }

                StringBuilder desc = new StringBuilder();
                for (DatabaseManager.SongSuggestionRecord song : songs) {
                    desc.append("`#").append(song.songId).append("` ")
                            .append("**").append(truncateText(song.title, 40)).append("**")
                            .append(" — ").append(truncateText(song.artist, 30))
                            .append("\n↳ ").append(song.source)
                            .append(" • added by <@").append(song.addedBy).append(">")
                            .append("\n\n");
                }

                EmbedBuilder embed = new EmbedBuilder()
                        .setColor(new Color(186, 85, 211))
                        .setTitle("✦ AMORA SONG POOL ✦")
                        .setDescription(desc.toString())
                        .setFooter("Showing the 10 most recent active song submissions", null);

                event.replyEmbeds(embed.build()).setEphemeral(true).queue();
                return;
            }

            if (subcommand.equals("suggest")) {
                DatabaseManager.SongSuggestionRecord song = db.getRandomActiveSongSuggestion();

                if (song == null) {
                    event.reply("❌ There are no active song suggestions yet.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.replyEmbeds(
                        buildSongEmbed(song, "✦ RANDOM AMORA SONG PICK ✦", "AMORA Music Recommendation").build()
                ).queue();
                return;
            }

            if (subcommand.equals("postnow")) {
                boolean isAdmin = event.getMember() != null
                        && event.getMember().hasPermission(Permission.ADMINISTRATOR);

                if (!isAdmin) {
                    event.reply("❌ Only admins can force-post the daily song.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (DAILY_SONG_CHANNEL_ID == null || DAILY_SONG_CHANNEL_ID.isBlank()) {
                    event.reply("❌ DAILY_SONG_CHANNEL_ID is not configured.")
                            .setEphemeral(true).queue();
                    return;
                }

                boolean posted = App.postSongRecommendation(event.getJDA(), false);
                if (!posted) {
                    event.reply("❌ Could not post a song right now. Check the channel ID or make sure the pool has songs.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.reply("✅ Song recommendation posted in <#" + DAILY_SONG_CHANNEL_ID + ">.")
                        .setEphemeral(true).queue();
                return;
            }

            event.reply("❌ Unknown song subcommand.")
                    .setEphemeral(true).queue();
            return;
            }
        }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String componentId = event.getComponentId();
        DatabaseManager db = DatabaseManager.getInstance();

        if (componentId.startsWith("offer_") || componentId.startsWith("req_")) {
            String setupId = componentId.substring(componentId.indexOf('_') + 1);
            DatabaseManager.PendingTradeSetupRecord setup = db.getPendingTradeSetup(setupId);

            if (setup == null) {
                event.reply("❌ This trade setup has expired.").setEphemeral(true).queue();
                return;
            }

            String selectedValue = event.getValues().get(0);
            if (componentId.startsWith("offer_")) {
                db.updatePendingTradeOffer(setupId, selectedValue);
            } else {
                db.updatePendingTradeRequest(setupId, selectedValue);
            }

            event.deferEdit().queue();
            return;
        }

        if (componentId.startsWith("forge_craft_")) {
            String ownerId = componentId.substring("forge_craft_".length());

            if (!event.getUser().getId().equals(ownerId)) {
                event.reply("❌ This is not your forge session!").setEphemeral(true).queue();
                return;
            }

            String selectedIngredient = event.getValues().get(0);
            if (selectedIngredient.equals("none")) {
                event.deferEdit().queue();
                return;
            }

            String currentInv = db.getInventory(ownerId);
            if (getItemCount(currentInv, selectedIngredient) < 3) {
                event.reply("❌ You no longer have 3 of these to craft!").setEphemeral(true).queue();
                return;
            }

            db.savePendingForge(ownerId, selectedIngredient, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));

            StringSelectMenu.Builder claimMenu = StringSelectMenu.create("forge_claim_" + ownerId)
                    .setPlaceholder("✨ Choose your upgraded reward...");

            for (String reward : forgeRewards(selectedIngredient)) {
                claimMenu.addOption(reward, reward);
            }

            EmbedBuilder step2Embed = new EmbedBuilder()
                    .setColor(new Color(138, 43, 226))
                    .setTitle("THE SYNTHESIS FORGE — REWARD SELECTION")
                    .setDescription("You are burning **3x " + selectedIngredient + "**.\n\nWhich reward would you like to mold from the ashes?");

            event.editMessageEmbeds(step2Embed.build()).setActionRow(claimMenu.build()).queue();
            return;
        }

        if (componentId.startsWith("forge_claim_")) {
            String ownerId = componentId.substring("forge_claim_".length());

            if (!event.getUser().getId().equals(ownerId)) {
                event.reply("❌ Not your session!").setEphemeral(true).queue();
                return;
            }

            String selectedReward = event.getValues().get(0);
            String ingredient = db.getPendingForgeIngredient(ownerId);

            if (ingredient == null) {
                event.reply("❌ Forge session expired. Try again.").setEphemeral(true).queue();
                return;
            }

            synchronized (this) {
                String currentInv = db.getInventory(ownerId);
                if (getItemCount(currentInv, ingredient) < 3) {
                    event.reply("❌ You no longer have 3x of the ingredient!").setEphemeral(true).queue();
                    db.deletePendingForge(ownerId);
                    return;
                }

                String newInv = removeMultipleItems(currentInv, ingredient, 3);
                newInv = newInv.isEmpty() ? selectedReward : newInv + "," + selectedReward;
                db.updateInventory(ownerId, newInv);
            }

            db.deletePendingForge(ownerId);

            EmbedBuilder success = new EmbedBuilder()
                    .setColor(new Color(255, 215, 0))
                    .setTitle("✦ SYNTHESIS SUCCESS ✦")
                    .setDescription(
                            "The forge answered your offering and reshaped it into something rarer.\n\n" +
                            "*From ash and shimmer, a new treasure was born.*"
                    )
                    .addField("🔥 Consumed", "`3x " + ingredient + "`", true)
                    .addField("✨ Forged", "`" + selectedReward + "`", true)
                    .addField("📥 Destination", "Safely placed into your inventory.", false)
                    .setFooter("AMORA Synthesis Forge", null);

            event.replyEmbeds(success.build()).queue();
            event.getMessage().delete().queue();
            sendAuditLog(event.getGuild(), "Forge Crafted",
                    event.getUser().getAsMention() + " burned 3x **" + ingredient + "** and crafted **"
                            + selectedReward + "**.", new Color(255, 215, 0));
            return;
        }

        if (componentId.startsWith("forge_recycle_")) {
            String ownerId = componentId.substring("forge_recycle_".length());

            if (!event.getUser().getId().equals(ownerId)) {
                event.reply("❌ This is not your forge session!").setEphemeral(true).queue();
                return;
            }

            String selected = event.getValues().get(0);

            synchronized (this) {
                String currentInv = db.getInventory(ownerId);
                if (getItemCount(currentInv, selected) < 1) {
                    event.reply("❌ You do not have this item anymore!").setEphemeral(true).queue();
                    return;
                }

                int sparks = recycleValue(selected);
                String newInv = removeMultipleItems(currentInv, selected, 1);
                db.updateInventory(ownerId, newInv);

                int curSparks = db.getSparks(ownerId);
                db.updateSparks(ownerId, curSparks + sparks);

                EmbedBuilder success = new EmbedBuilder()
                        .setColor(new Color(80, 200, 120))
                        .setTitle("✦ RECYCLING COMPLETE ✦")
                        .setDescription(
                                "The item has been dissolved and returned to raw energy.\n\n" +
                                "*Nothing precious is ever truly wasted in the forge.*"
                        )
                        .addField("♻️ Recycled Item", "`" + selected + "`", false)
                        .addField("⚡ Sparks Recovered", "`+" + sparks + " Sparks`", true)
                        .addField("🏦 New Balance", "`" + (curSparks + sparks) + " Sparks`", true)
                        .setFooter("AMORA Recovery Forge", null);

                event.replyEmbeds(success.build()).queue();
                event.getMessage().delete().queue();
                sendAuditLog(event.getGuild(), "Item Recycled",
                        event.getUser().getAsMention() + " recycled **" + selected + "** for `"
                                + sparks + " Sparks`.", Color.LIGHT_GRAY);
            }
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        DatabaseManager db = DatabaseManager.getInstance();

        if (componentId.equals("bjoin_button")) {
            if (!event.getChannel().getType().isThread()) {
                event.reply("❌ This button can only be used inside a quest thread.").setEphemeral(true).queue();
                return;
            }

            ThreadChannel thread = event.getChannel().asThreadChannel();

            event.deferReply(true).queue(hook ->
                    thread.retrieveStartMessage().queue(startMsg -> {
                        MessageEmbed oldEmbed = startMsg.getEmbeds().get(0);
                        EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);

                        String[] partyField = getPartyField(oldEmbed);
                        int fieldIndex = getPartyFieldIndex(oldEmbed);

                        if (partyField == null || fieldIndex == -1) {
                            hook.sendMessage("❌ Party field missing.").setEphemeral(true).queue();
                            return;
                        }

                        String partyName = partyField[0];
                        String partyValue = partyField[1];
                        int current = parsePartyCurrent(partyName);
                        String maxStr = parsePartyMax(partyName);
                        int max = maxStr.equalsIgnoreCase("Unlimited")
                                ? Integer.MAX_VALUE
                                : Integer.parseInt(maxStr);
                        String userMention = event.getUser().getAsMention();

                        if (partyValue.contains(userMention)) {
                            hook.sendMessage("❌ You are already in the party!").setEphemeral(true).queue();
                            return;
                        }

                        if (current >= max) {
                            hook.sendMessage("❌ This party is full!").setEphemeral(true).queue();
                            return;
                        }

                        partyValue = partyValue.equals("None") ? userMention : partyValue + "\n" + userMention;
                        current++;

                        newEmbed.getFields().remove(fieldIndex);
                        newEmbed.addField("👥 Party [" + current + "/" + maxStr + "]", partyValue, false);
                        newEmbed.setColor(Color.YELLOW);

                        Button joinButton = current >= max
                                ? Button.success("bjoin_button", "✋ Join Quest").asDisabled()
                                : Button.success("bjoin_button", "✋ Join Quest");

                        startMsg.editMessageEmbeds(newEmbed.build())
                                .setActionRow(joinButton, Button.danger("bleave_button", "🛑 Leave Quest"))
                                .queue(
                                        success -> hook.sendMessage("✅ You have successfully joined the party!").setEphemeral(true).queue(),
                                        error -> hook.sendMessage("❌ Failed to update the quest party: " + error.getMessage()).setEphemeral(true).queue()
                                );
                    }, error -> hook.sendMessage("❌ Failed to fetch the quest starter message.").setEphemeral(true).queue())
            );
            return;
        }

        if (componentId.equals("bleave_button")) {
            if (!event.getChannel().getType().isThread()) {
                event.reply("❌ This button can only be used inside a quest thread.").setEphemeral(true).queue();
                return;
            }

            ThreadChannel thread = event.getChannel().asThreadChannel();

            event.deferReply(true).queue(hook ->
                    thread.retrieveStartMessage().queue(startMsg -> {
                        MessageEmbed oldEmbed = startMsg.getEmbeds().get(0);
                        EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);

                        String[] partyField = getPartyField(oldEmbed);
                        int fieldIndex = getPartyFieldIndex(oldEmbed);

                        if (partyField == null || fieldIndex == -1) {
                            hook.sendMessage("❌ Party field missing.").setEphemeral(true).queue();
                            return;
                        }

                        String partyName = partyField[0];
                        String partyValue = partyField[1];
                        int current = parsePartyCurrent(partyName);
                        String maxStr = parsePartyMax(partyName);
                        String userMention = event.getUser().getAsMention();

                        if (!partyValue.contains(userMention)) {
                            hook.sendMessage("❌ You are not in the party!").setEphemeral(true).queue();
                            return;
                        }

                        partyValue = partyValue.replace(userMention + "\n", "")
                                .replace("\n" + userMention, "")
                                .replace(userMention, "");

                        if (partyValue.trim().isEmpty()) {
                            partyValue = "None";
                        }

                        current--;

                        newEmbed.getFields().remove(fieldIndex);
                        newEmbed.addField("👥 Party [" + current + "/" + maxStr + "]", partyValue, false);

                        if (current == 0) {
                            newEmbed.setColor(new Color(255, 69, 0));
                        }

                        startMsg.editMessageEmbeds(newEmbed.build())
                                .setActionRow(
                                        Button.success("bjoin_button", "✋ Join Quest"),
                                        Button.danger("bleave_button", "🛑 Leave Quest")
                                )
                                .queue(
                                        success -> hook.sendMessage("✅ You have left the party.").setEphemeral(true).queue(),
                                        error -> hook.sendMessage("❌ Failed to update the quest party: " + error.getMessage()).setEphemeral(true).queue()
                                );
                    }, error -> hook.sendMessage("❌ Failed to fetch the quest starter message.").setEphemeral(true).queue())
            );
            return;
        }

        if (componentId.startsWith("buy_")) {
            String[] parts = componentId.split("_", 3);
            if (parts.length < 3) {
                event.reply("❌ Invalid purchase payload.").setEphemeral(true).queue();
                return;
            }

            int price = Integer.parseInt(parts[1]);
            String itemName = decodeItem(parts[2]);
            String clickerId = event.getUser().getId();

            event.deferReply(true).queue();
            synchronized (this) {
                int currentPoints = db.getPoints(clickerId);
                if (getExactItemName(db.getInventory(clickerId), itemName) != null) {
                    event.getHook().sendMessage("❌ You already own this asset.").queue();
                    return;
                }
                if (currentPoints < price) {
                    event.getHook().sendMessage("❌ Not enough Points!").queue();
                    return;
                }

                db.updatePoints(clickerId, currentPoints - price);
                db.addInventoryItem(clickerId, itemName);
            }

            EmbedBuilder checkoutEmbed = new EmbedBuilder()
                    .setColor(new Color(46, 204, 113))
                    .setTitle("✦ SECURE CHECKOUT COMPLETE ✦")
                    .setDescription(
                            "Your purchase has been processed successfully.\n\n" +
                            "📦 **Asset Added:** `" + itemName + "`\n" +
                            "💌 **Delivery Status:** Check your DMs for the secure package.\n\n" +
                            "*Thank you for supporting the AMORA Asset Market.*"
                    )
                    .setFooter("AMORA Secure Commerce System", null);

            event.getHook().sendMessageEmbeds(checkoutEmbed.build()).queue();

            event.getUser().openPrivateChannel().flatMap(channel -> {
                EmbedBuilder deliveryEmbed = new EmbedBuilder()
                        .setColor(new Color(138, 43, 226))
                        .setTitle("✦ ASSET DELIVERY: " + itemName.toUpperCase() + " ✦")
                        .setDescription(
                                "Thank you for your purchase from the AMORA Asset Market.\n\n" +
                                "📦 **Your Secure Delivery Data:**\n" +
                                "`" + db.getSecretLink(itemName) + "`\n\n" +
                                "💜 **Delivery Note:** This package was prepared exclusively for you.\n\n" +
                                "*Please keep this information strictly confidential.*"
                        )
                        .setFooter("AMORA Curated Ecosystem", null);
                return channel.sendMessageEmbeds(deliveryEmbed.build());
            }).queue(success -> {
            }, error -> event.getChannel().sendMessage(
                    event.getUser().getAsMention() + " ⚠️ I couldn’t send your delivery because your DMs are closed."
            ).queue());

            sendAuditLog(
                    event.getGuild(),
                    "Shop Purchase",
                    event.getUser().getAsMention() + " purchased **" + itemName + "** from the market for `"
                            + price + " Points`.",
                    new Color(138, 43, 226)
            );
            return;
        }

        if (componentId.startsWith("cancelsetup_")) {
            String setupId = componentId.substring("cancelsetup_".length());
            db.deletePendingTradeSetup(setupId);

            event.editMessageEmbeds(new EmbedBuilder()
                    .setColor(Color.RED)
                    .setDescription("❌ Trade setup cancelled.")
                    .build()).setComponents().queue();
            return;
        }

        if (componentId.startsWith("propose_")) {
            String setupId = componentId.substring("propose_".length());
            DatabaseManager.PendingTradeSetupRecord setup = db.getPendingTradeSetup(setupId);

            if (setup == null || setup.selectedOffer == null || setup.selectedRequest == null) {
                event.reply("❌ Invalid or expired trade setup.").setEphemeral(true).queue();
                return;
            }

            String tradeId = UUID.randomUUID().toString().substring(0, 8);
            long tradeExpiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15);

            db.saveActiveTrade(
                    tradeId,
                    setup.senderId,
                    setup.targetId,
                    setup.selectedOffer,
                    setup.selectedRequest,
                    tradeExpiresAt
            );

            event.editMessageEmbeds(new EmbedBuilder()
                    .setColor(new Color(80, 200, 120))
                    .setTitle("✦ PROPOSAL DISPATCHED ✦")
                    .setDescription(
                            "Your exchange request has been sent successfully.\n\n" +
                            "*Now we wait for the other collector to respond.*"
                    )
                    .setFooter("AMORA Exchange Network", null)
                    .build()
            ).setComponents().queue();

            EmbedBuilder tradeEmbed = new EmbedBuilder()
                    .setColor(new Color(138, 43, 226))
                    .setTitle("✦ ASSET EXCHANGE PROPOSAL ✦")
                    .setDescription(
                            "<@" + setup.senderId + "> has prepared an exchange request for <@" + setup.targetId + ">.\n\n" +
                            "*A thoughtful trade can complete both collections beautifully.*"
                    )
                    .addField("📤 Offered Asset", "`" + setup.selectedOffer + "`", false)
                    .addField("📥 Requested Asset", "`" + setup.selectedRequest + "`", false)
                    .setFooter("AMORA Exchange Network • Proposal expires in 15 minutes", null);

            event.getChannel().sendMessageEmbeds(tradeEmbed.build())
                    .addActionRow(
                            Button.success("trade_accept_" + tradeId, "✅ Accept"),
                            Button.danger("trade_decline_" + tradeId, "❌ Decline")
                    )
                    .queue();

            db.deletePendingTradeSetup(setupId);
            return;
        }

        if (componentId.startsWith("trade_accept_") || componentId.startsWith("trade_decline_")) {
            String[] parts = componentId.split("_", 3);
            String action = parts[1];
            String tradeId = parts[2];

            DatabaseManager.ActiveTradeRecord trade = db.getActiveTrade(tradeId);

            if (trade == null) {
                event.editComponents().queue();
                event.getChannel().sendMessage("❌ Trade expired.").queue();
                return;
            }

            if (!event.getUser().getId().equals(trade.targetId) && !event.getUser().getId().equals(trade.senderId)) {
                event.reply("❌ You are not involved in this trade.").setEphemeral(true).queue();
                return;
            }

            if (action.equals("decline")) {
                db.deleteActiveTrade(tradeId);
                event.editComponents().queue();
                event.getChannel().sendMessage("❌ Trade cancelled.").queue();
                return;
            }

            if (!event.getUser().getId().equals(trade.targetId)) {
                event.reply("❌ Only the target can accept this trade.").setEphemeral(true).queue();
                return;
            }

            synchronized (this) {
                String senderInv = db.getInventory(trade.senderId);
                String targetInv = db.getInventory(trade.targetId);

                if (getExactItemName(senderInv, trade.offerItem) == null
                        || getExactItemName(targetInv, trade.requestItem) == null) {
                    db.deleteActiveTrade(tradeId);
                    event.editComponents().queue();
                    event.getChannel().sendMessage("❌ Trade voided. One or more items are missing.").queue();
                    return;
                }

                String newSenderInv = removeItem(senderInv, trade.offerItem);
                newSenderInv = newSenderInv.isEmpty() ? trade.requestItem : newSenderInv + "," + trade.requestItem;

                String newTargetInv = removeItem(targetInv, trade.requestItem);
                newTargetInv = newTargetInv.isEmpty() ? trade.offerItem : newTargetInv + "," + trade.offerItem;

                db.updateInventory(trade.senderId, newSenderInv);
                db.updateInventory(trade.targetId, newTargetInv);
            }

            db.deleteActiveTrade(tradeId);
            event.editComponents().queue();

            EmbedBuilder completedTrade = new EmbedBuilder()
                    .setColor(new Color(50, 205, 50))
                    .setTitle("✦ EXCHANGE COMPLETE ✦")
                    .setDescription(
                            "The trade has been finalized successfully.\n\n" +
                            "*A fair exchange leaves both collections a little more complete.*"
                    )
                    .addField("🤝 Participants", "<@" + trade.senderId + "> ↔ <@" + trade.targetId + ">", false)
                    .addField("📤 From Sender", "`" + trade.offerItem + "`", true)
                    .addField("📥 From Target", "`" + trade.requestItem + "`", true)
                    .setFooter("AMORA Exchange Network", null);

            event.getChannel().sendMessageEmbeds(completedTrade.build()).queue();

            sendAuditLog(event.getGuild(), "Trade Executed",
                    "<@" + trade.senderId + "> traded **" + trade.offerItem + "** to <@"
                            + trade.targetId + "> for **" + trade.requestItem + "**.",
                    new Color(50, 205, 50));
        }
    }
}