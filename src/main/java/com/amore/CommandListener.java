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
import java.net.URLDecoder;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

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
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

public class CommandListener extends ListenerAdapter {

    private static final ExecutorService scheduler = Executors.newSingleThreadExecutor();
    private static final String AUDIT_LOG_CHANNEL_ID = System.getenv("AUDIT_LOG_CHANNEL_ID");
    private static final String SHOP_LOG_CHANNEL_ID = System.getenv("SHOP_LOG_CHANNEL_ID");
    private static final String SHOP_FORUM_CHANNEL_ID = System.getenv("SHOP_FORUM_CHANNEL_ID");
    private static final String STANDARD_BOUNTY_FORUM_ID = System.getenv("STANDARD_BOUNTY_FORUM_ID");
    private static final String URGENT_BOUNTY_FORUM_ID = System.getenv("URGENT_BOUNTY_FORUM_ID");
    private static final String ADDSPARKS_ROLE_IDS = System.getenv("ADDSPARKS_ROLE_IDS");
    private static final String PAYOUT_ROLE_IDS = System.getenv("PAYOUT_ROLE_IDS");
    private static final String DAILY_SONG_CHANNEL_ID = System.getenv("DAILY_SONG_CHANNEL_ID");
    private static final String MUSIC_ADMIN_ROLE_IDS = System.getenv("MUSIC_ADMIN_ROLE_IDS");
    private static final String ORDER_CHANNEL_ID = System.getenv("ORDER_CHANNEL_ID");
    private static final String MEMBER_ROLE_ID = System.getenv("MEMBER_ROLE_ID");

    public static final String MIKU_SAD = "<:1MikuSad:1511388491429449850>";
    public static final String XB_CUTE = "<a:1_xbcute:1514916160200507523>";
    public static final String CINNA_HIDE = "<a:009BCinnamoroll_Hide:1512617579154378833> ";
    public static final String BUGCAT_OK = "<a:BugCatOk:1526913455108657222>";
    public static final String CinnaSurprise = "<a:8_cinnasurprise:1512108709185060897>";

    private static final Set<String> processedInteractions = ConcurrentHashMap.newKeySet();
    private static final Set<String> movingCarts = ConcurrentHashMap.newKeySet();
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

    private void sendShopLog(Guild guild, String title, String description, Color color) {
        if (guild == null || SHOP_LOG_CHANNEL_ID == null || SHOP_LOG_CHANNEL_ID.isBlank()) {
            return;
        }

        TextChannel shopLogChannel = guild.getTextChannelById(SHOP_LOG_CHANNEL_ID);
        if (shopLogChannel != null) {
            EmbedBuilder logEmbed = new EmbedBuilder()
                    .setColor(color)
                    .setTitle("🛒 SHOP AUDIT: " + title)
                    .setDescription(description)
                    .setTimestamp(Instant.now());
            shopLogChannel.sendMessageEmbeds(logEmbed.build()).queue();
        }
    }
    
    private void generateAndLogTranscript(ThreadChannel thread, String status) {
        if (SHOP_LOG_CHANNEL_ID == null || SHOP_LOG_CHANNEL_ID.isBlank()) return;
        TextChannel logChannel = thread.getGuild().getTextChannelById(SHOP_LOG_CHANNEL_ID);
        if (logChannel == null) return;

        thread.getIterableHistory().takeAsync(1000).thenAccept(messages -> {
            StringBuilder fileContent = new StringBuilder();
            StringBuilder embedDesc = new StringBuilder();
            
            List<String> previewImages = new ArrayList<>();

            fileContent.append("✦ ORDER TRANSCRIPT: ").append(thread.getName()).append(" ✦\n");
            fileContent.append("Status: ").append(status).append("\n");
            fileContent.append("=========================================\n\n");

            java.util.Collections.reverse(messages);

            for (net.dv8tion.jda.api.entities.Message msg : messages) {
                fileContent.append("[").append(msg.getTimeCreated().toLocalDateTime().toString()).append("] ");
                fileContent.append(msg.getAuthor().getName()).append(": ");
                fileContent.append(msg.getContentDisplay()).append("\n");
                
                String embedLine = "**" + msg.getAuthor().getName() + "**: " + msg.getContentDisplay() + "\n";
                if (embedDesc.length() + embedLine.length() < 3800) {
                    embedDesc.append(embedLine);
                }

                if (!msg.getAttachments().isEmpty()) {
                    fileContent.append("   [Attachments Uploaded]:\n");
                    for (net.dv8tion.jda.api.entities.Message.Attachment attachment : msg.getAttachments()) {
                        fileContent.append("      -> ").append(attachment.getUrl()).append("\n");
                        
                        if (attachment.isImage() && previewImages.size() < 4) {
                            previewImages.add(attachment.getUrl());
                        }
                    }
                }
            }

            if (embedDesc.length() >= 3800) {
                embedDesc.append("\n*... (Chat truncated. Download the .txt file below to read the rest!)*");
            }

            byte[] fileBytes = fileContent.toString().getBytes(StandardCharsets.UTF_8);
            String safeThreadName = thread.getName().replaceAll("[^a-zA-Z0-9_-]", "");
            FileUpload upload = FileUpload.fromData(fileBytes, "Transcript_" + safeThreadName + ".txt");

            List<MessageEmbed> finalEmbeds = new ArrayList<>();

            EmbedBuilder transcriptEmbed = new EmbedBuilder()
                    .setColor(status.equals("COMPLETED") ? new Color(50, 205, 50) : Color.RED)
                    .setTitle("✦ TRANSCRIPT: " + thread.getName() + " ✦")
                    .setUrl(thread.getJumpUrl()) 
                    .setDescription(embedDesc.length() > 0 ? embedDesc.toString() : "*No messages recorded.*")
                    .addField("Final Status", "`" + status + "`", true)
                    .setFooter("AMORA Secure Logging System", null)
                    .setTimestamp(Instant.now());

            if (!previewImages.isEmpty()) {
                transcriptEmbed.setImage(previewImages.get(0));
            }
            finalEmbeds.add(transcriptEmbed.build());

            for (int i = 1; i < previewImages.size(); i++) {
                EmbedBuilder extraImageEmbed = new EmbedBuilder()
                        .setUrl(thread.getJumpUrl()) 
                        .setImage(previewImages.get(i));
                finalEmbeds.add(extraImageEmbed.build());
            }

            logChannel.sendMessageEmbeds(finalEmbeds)
                      .addFiles(upload)
                      .queue();
                      
        }).exceptionally(e -> {
            System.out.println("  Failed to automatically generate transcript: " + e.getMessage());
            return null;
        });
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
            event.reply("  This command can only be used inside a server.")
                    .setEphemeral(true).queue();
            return false;
        }

        if (rawRoleIds == null || rawRoleIds.isBlank()) {
            event.reply("  `" + envName + "` is not allowed to use this command wahhh T^T.")
                    .setEphemeral(true).queue();
            return false;
        }

        if (!hasAnyAllowedRole(event, rawRoleIds)) {
            event.reply("  You do not have any of the required roles to use this command.")
                    .setEphemeral(true).queue();
            return false;
        }

        return true;
    }

    private boolean isMusicStaff(SlashCommandInteractionEvent event) {
        if (event.getMember() == null) return false;
        if (event.getMember().hasPermission(Permission.ADMINISTRATOR)) return true;
        
        if (MUSIC_ADMIN_ROLE_IDS != null && !MUSIC_ADMIN_ROLE_IDS.isBlank()) {
            return hasAnyAllowedRole(event, MUSIC_ADMIN_ROLE_IDS);
        }
        return false;
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
    
    private String getCustomEmoji(Guild guild, String emojiName, String fallback) {
        if (guild == null) return fallback;
        return guild.getEmojisByName(emojiName, true).stream()
                .findFirst()
                .map(net.dv8tion.jda.api.entities.emoji.RichCustomEmoji::getAsMention)
                .orElse(fallback);
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

        String lower = link.trim().toLowerCase(Locale.ROOT);
        if ((lower.contains("youtube.com") || lower.contains("youtu.be")) && lower.contains("list=")) {
            return true;
        }
        if (link.startsWith("PL") || link.startsWith("OL") || link.startsWith("RD") || link.startsWith("UU")) {
            return true;
        }
        
        return false;
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
        EmbedBuilder eb = new EmbedBuilder()
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
        String artworkUrl = fetchSongArtwork(song.link);
        if (artworkUrl != null && !artworkUrl.isBlank()) {
            eb.setImage(artworkUrl);
        }
        return eb;
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
                description = "  Failed to read the attached .txt file.";
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
        return Base64.getUrlEncoder().encodeToString(itemName.getBytes(StandardCharsets.UTF_8));
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
    
    public static String fetchSongArtwork(String link) {
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

    private static String fetchSpotifyThumbnail(String link) {
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

    public static String fetchYouTubeThumbnail(String link) {
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
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) return;
        if (event.getAuthor().isBot()) return;

        if (event.getChannelType() == ChannelType.GUILD_PRIVATE_THREAD) {
            ThreadChannel thread = event.getChannel().asThreadChannel();
            
            if (ORDER_CHANNEL_ID != null && thread.getParentChannel().getId().equals(ORDER_CHANNEL_ID)) {
                
                if (thread.getName().startsWith("⏳")) {
                    event.getMessage().delete().queue();
                    
                    event.getChannel().sendMessage(event.getAuthor().getAsMention() + " " + MIKU_SAD + " **Hold on!** The creator must click **Accept Order** before you can start chatting.")
                         .queue(msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS));
                    return;
                }

                if (thread.getName().startsWith("🔒")) {
                    
                    if (movingCarts.contains(thread.getId())) return;
                    
                    thread.getHistory().retrievePast(15).queue(messages -> {
                        net.dv8tion.jda.api.entities.Message cartMsg = null;
                        int cartIndex = -1;

                        for (int i = 0; i < messages.size(); i++) {
                            net.dv8tion.jda.api.entities.Message msg = messages.get(i);
                            if (msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                                if (!msg.getButtons().isEmpty()) {
                                    Button firstButton = msg.getButtons().get(0);
                                    if (firstButton.getId() != null && firstButton.getId().startsWith("buyerconfirm_") && !firstButton.isDisabled()) {
                                        cartMsg = msg;
                                        cartIndex = i;
                                        break;
                                    }
                                }
                            }
                        }

                        if (cartMsg != null && cartIndex >= 1) {
                            List<MessageEmbed> embeds = cartMsg.getEmbeds();
                            List<net.dv8tion.jda.api.interactions.components.ActionRow> components = cartMsg.getActionRows();
                            if (!embeds.isEmpty()) {
                                movingCarts.add(thread.getId()); 
                                thread.sendMessageEmbeds(embeds).setComponents(components).queue(
                                    newMsg -> movingCarts.remove(thread.getId()),
                                    error -> movingCarts.remove(thread.getId())
                                );
                                cartMsg.delete().queue();
                            }
                        }
                    });
                }
            }
        }
    }
    
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getId();
        DatabaseManager db = DatabaseManager.getInstance();
        if (event.getName().equals("eventsetup")) {
            if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("  Director clearance required.").setEphemeral(true).queue();
                return;
            }

            EmbedBuilder panelEmbed = new EmbedBuilder()
                .setColor(new Color(255, 182, 193))
                .setTitle("✦ AMORA HYBRID EVENT CREATOR ✦")
                .setDescription("Select an event type below.\n\nThe bot will automatically route your event to the correct Forum and drop the ping in the Schedules channel!")
                .setFooter("AMORA Auto-Routing System", null);

            StringSelectMenu menu = StringSelectMenu.create("menu_fused")
                .setPlaceholder("📢 Select Event, Audience & Urgency...")
                .addOption("🌍 Training (Everyone)", "training:everyone:standard", "Posts in Bounties, Pings Schedules")
                .addOption("🌍 Movie Night (Everyone)", "movie:everyone:standard", "Posts in Bounties, Pings Schedules")
                .addOption("🌍 Game Night (Everyone)", "game:everyone:standard", "Posts in Bounties, Pings Schedules")
                .addOption("🌍 Photoshoot (Everyone)", "photo:everyone:standard", "Posts in Bounties, Pings Schedules")
                .addOption("🌍 Mini Comp (Everyone)", "mini_comp:everyone:standard", "Posts in Bounties, Pings Schedules")
                .addOption("🌍 Fashion Show (Everyone)", "fashion:everyone:standard", "Posts in Bounties, Pings Schedules")
                .addOption("🌍 Training Comp (Everyone)", "training_comp:everyone:standard", "Posts in Bounties, Pings Schedules")
                .addOption("👑 Training (Members)", "training:member:standard", "Posts in Bounties, Pings Schedules")
                .addOption("👑 Movie Night (Members)", "movie:member:standard", "Posts in Bounties, Pings Schedules")
                .addOption("👑 Game Night (Members)", "game:member:standard", "Posts in Bounties, Pings Schedules")
                .addOption("👑 Photoshoot (Members)", "photo:member:standard", "Posts in Bounties, Pings Schedules")
                .addOption("👑 Mini Comp (Members)", "mini_comp:member:standard", "Posts in Bounties, Pings Schedules")
                .addOption("👑 Fashion Show (Members)", "fashion:member:standard", "Posts in Bounties, Pings Schedules")
                .addOption("👑 Training Comp (Members)", "training_comp:member:standard", "Posts in Bounties, Pings Schedules")
                .addOption("🚨 URGENT: Training (Members)", "training:member:urgent", "Posts in Urgent, Pings Schedules")
                .addOption("🚨 URGENT: Movie Night (Members)", "movie:member:urgent", "Posts in Urgent, Pings Schedules")
                .addOption("🚨 URGENT: Game Night (Members)", "game:member:urgent", "Posts in Urgent, Pings Schedules")
                .addOption("🚨 URGENT: Photoshoot (Members)", "photo:member:urgent", "Posts in Urgent, Pings Schedules")
                .addOption("🚨 URGENT: Mini Comp (Members)", "mini_comp:member:urgent", "Posts in Urgent, Pings Schedules")
                .addOption("🚨 URGENT: Fashion Show (Members)", "fashion:member:urgent", "Posts in Urgent, Pings Schedules")
                .addOption("🚨 URGENT: Training Comp (Members)", "training_comp:member:urgent", "Posts in Urgent, Pings Schedules")
                .build();

            event.getChannel().sendMessageEmbeds(panelEmbed.build())
                .addActionRow(menu)
                .queue();
                
            event.reply("✅ Auto-Routing Hybrid Event panel deployed!").setEphemeral(true).queue();
            return;
        }
        if (event.getName().equals("order")) {
            if (ORDER_CHANNEL_ID != null && !event.getChannel().getId().equals(ORDER_CHANNEL_ID)) {
                event.reply("  **Rule 07 Violation:** Please place all orders in the <#" + ORDER_CHANNEL_ID + "> channel!").setEphemeral(true).queue();
                return;
            }

            User creator = event.getOption("creator").getAsUser();
            String request = event.getOption("request").getAsString();
            String notes = event.getOption("notes") != null ? event.getOption("notes").getAsString() : "_None provided._";
            Attachment image = event.getOption("image") != null ? event.getOption("image").getAsAttachment() : null;

            if (creator.isBot() || creator.getId().equals(event.getUser().getId())) {
                event.reply("  You cannot place an order with yourself or a bot.").setEphemeral(true).queue();
                return;
            }

            TextChannel orderChannel = event.getJDA().getTextChannelById(ORDER_CHANNEL_ID);
            if (orderChannel != null) {
                
                java.util.Optional<ThreadChannel> activeThread = orderChannel.getThreadChannels().stream()
                        .filter(t -> t.getName().contains(event.getUser().getName() + " ➔ " + creator.getName()))
                        .findFirst();

                if (activeThread.isPresent()) {
                    event.reply(MIKU_SAD + " **Hold on!** You already have an open transaction with **" + creator.getName() + "** in " + activeThread.get().getAsMention() + "!\n\n🛒 **Want to buy this too?**\nJust share what you want inside that thread and use the `➕ Add Item` button to buy them all at once!").setEphemeral(true).queue();
                    return;
                }

                long buyerTotalActiveOrders = orderChannel.getThreadChannels().stream()
                        .filter(t -> t.getName().contains(event.getUser().getName() + " ➔"))
                        .count();

                if (buyerTotalActiveOrders >= 5) {
                    event.reply(MIKU_SAD + " **Cart Full!** You currently have 5 active orders open. Please finish or cancel an existing transaction before adding more items to your cart!").setEphemeral(true).queue();
                    return;
                }

                long creatorActiveOrders = orderChannel.getThreadChannels().stream()
                        .filter(t -> t.getName().contains("➔ " + creator.getName()))
                        .count();

                if (creatorActiveOrders >= 5) {
                    event.reply(XB_CUTE + " **Queue Full!** " + creator.getName() + " currently has 5 active orders. Please wait for them to finish some commissions before ordering from them!").setEphemeral(true).queue();
                    return;
                }

                EmbedBuilder orderEmbed = new EmbedBuilder()
                        .setColor(new Color(255, 182, 193))
                        .setTitle("✦ NEW COMMISSION ORDER ✦")
                        .setDescription(
                                CINNA_HIDE + " **Request:** `" + request + "`\n\n" +
                                BUGCAT_OK + " **Notes & References:**\n" + notes + "\n\n" +
                                "*(Creator: Accept this order below to begin the transaction!)*"
                        )
                        .setThumbnail(event.getUser().getEffectiveAvatarUrl())
                        .setFooter("AMORA Designated Order System", null);

                if (image != null && image.isImage()) {
                    orderEmbed.setImage(image.getUrl());
                }

                String buyerName = event.getUser().getName();
                String creatorName = creator.getName();
                String ratingDisplay = db.getCreatorRatingString(creator.getId());

                String generatingUI = "# ------ᜊ  ⌒⌒ ⠀𓈒 𝐍𝐞𝐰 𝐎𝐫𝐝𝐞𝐫 🛒 ₊ ⊹---------\n" +
                                      ".⠀.   ᘛ   ˚⠀𝐀𝐌𝟎𝐑𝐀 **𝖲𝖬𝖠𝖱𝖳 𝖴𝖨** ! ˚\n\n" +
                                      "⋆ ˚｡⋆୨୧˚\n\n" +
                                      "۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪\n" +
                                      "ྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌ\n" +
                                      "֊   ᎔   ᎔   𑣩𑣨  ᎔   ᎔    ᎔   ᎔\n\n" +
                                      "_ _  ✩   𓏼    ׅ    ۟ 𐐂 Commission Request 𐐚 ✧.\n" +
                                      "_ _   ꒰ ଲ ꒱  ✦ **From:** " + buyerName + "\n" +
                                      "_ _   ꒰ Ꮼ ꒱  ✦ **For:** " + creatorName + "\n" +
                                      "_ _   ꒰ ⌾ ꒱  ✦ **Creator Rating:** " + ratingDisplay + "\n" +
                                      "_ _   ꒰ ⌾ ꒱  ✦ **Status:** ⏳ *Weaving the digital threads...*";
                orderChannel.sendMessage(generatingUI).queue(mainMessage -> {
                    
                    String shortId = UUID.randomUUID().toString().substring(0, 4);
                    
                    orderChannel.createThreadChannel("⏳ " + buyerName + " ➔ " + creatorName + " [" + shortId + "]", true).queue(thread -> {
                         
                         String readyUI = "# ------ᜊ  ⌒⌒ ⠀𓈒 𝐍𝐞𝐰 𝐎𝐫𝐝𝐞𝐫 🛒 ₊ ⊹---------\n" +
                                          ".⠀.   ᘛ   ˚⠀𝐀𝐌𝟎𝐑𝐀 **𝖲𝖬𝖠𝖱𝖳 𝖴𝖨** ! ˚\n\n" +
                                          "⋆ ˚｡⋆୨୧˚\n\n" +
                                          "۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪\n" +
                                          "ྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌ\n" +
                                          "֊   ᎔   ᎔   𑣩𑣨  ᎔   ᎔    ᎔   ᎔\n\n" +
                                          "_ _  ✩   𓏼    ׅ    ۟ 𐐂 Commission Request 𐐚 ✧.\n" +
                                          "_ _   ꒰ ଲ ꒱  ✦ **From:** " + buyerName + "\n" +
                                          "_ _   ꒰ Ꮼ ꒱  ✦ **For:** " + creatorName + "\n" +
                                          "_ _   ꒰ ⌾ ꒱  ✦ **Status:** 🔒 *Transaction Room Secured!*\n\n" +
                                          "<a:Saur_Heart:1525689248391368796>   _ _  ᨳ   𓏼    ׅ    ۟ 𐐂 Enter your Private Thread here: 𐐚 ೃ⁀➷\n" +
                                          "_ _   " + thread.getAsMention();
                         
                         mainMessage.editMessage(readyUI).queue();
                         
                         thread.addThreadMember(creator).queue();
                         thread.addThreadMember(event.getUser()).queue();

                         thread.sendMessage(creator.getAsMention() + " ✦ " + event.getUser().getAsMention() + "\nHere is your private transaction room 🔒! Please share all details and payment proofs here.")
                               .addEmbeds(orderEmbed.build())
                               .addActionRow(
                                   Button.success("orderaccept_" + creator.getId() + "_" + event.getUser().getId(), " Accept Order"),
                                   Button.danger("orderdecline_" + creator.getId() + "_" + event.getUser().getId(), "  Decline")
                               ).queue();
                    });
                });
            }
            return;
        }
        if (event.getName().equals("transcript")) {
            if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("  Director clearance required to pull transcripts.").setEphemeral(true).queue();
                return;
            }

            if (!event.getChannel().getType().isThread()) {
                event.reply("  This command must be run inside a private order thread!").setEphemeral(true).queue();
                return;
            }

            event.deferReply(true).queue();
            ThreadChannel thread = event.getChannel().asThreadChannel();

            thread.getIterableHistory().takeAsync(1000).thenAccept(messages -> {
                StringBuilder sb = new StringBuilder();
                sb.append("✦ ORDER TRANSCRIPT: ").append(thread.getName()).append(" ✦\n");
                sb.append("=========================================\n\n");

                java.util.Collections.reverse(messages);

                for (net.dv8tion.jda.api.entities.Message msg : messages) {
                    sb.append("[").append(msg.getTimeCreated().toLocalDateTime().toString()).append("] ");
                    sb.append(msg.getAuthor().getName()).append(": ");
                    sb.append(msg.getContentDisplay()).append("\n");
                    
                    if (!msg.getAttachments().isEmpty()) {
                        sb.append("   [Attachments Uploaded]:\n");
                        for (net.dv8tion.jda.api.entities.Message.Attachment attachment : msg.getAttachments()) {
                            sb.append("      -> ").append(attachment.getUrl()).append("\n");
                        }
                    }
                }

                byte[] fileBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                String safeThreadName = thread.getName().replaceAll("[^a-zA-Z0-9_-]", "");
                FileUpload upload = FileUpload.fromData(fileBytes, "Transcript_" + safeThreadName + ".txt");

                event.getHook().sendMessage("📂 **Transcript Generated!** Here is the complete chat log with image links for this order.")
                     .addFiles(upload)
                     .queue();
                     
                sendAuditLog(event.getGuild(), "Transcript Exported", 
                    event.getUser().getAsMention() + " exported a manual transcript for thread `" + thread.getName() + "`.", 
                    Color.LIGHT_GRAY);

            }).exceptionally(e -> {
                event.getHook().sendMessage("  Failed to generate transcript: " + e.getMessage()).queue();
                return null;
            });
            return;
        }
        if (event.getName().equals("shop")) {
            if ("evaluate".equals(event.getSubcommandName())) {
                if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                    event.reply("  Director clearance required.").setEphemeral(true).queue();
                    return;
                }
                
                boolean doReset = event.getOption("reset") != null ? event.getOption("reset").getAsBoolean() : false;
                List<String> compensated = db.generateCompensationReport(false); 
                
                StringBuilder log = new StringBuilder("The monthly shop performance audit is complete.\n\n");
                log.append("🔍 **Creators Flagged for Manual Review (0 Sales):**\n");
                
                if (compensated.isEmpty()) {
                    log.append("_Amazing! Everyone who listed an item successfully made a sale! No compensation needed._");
                } else {
                    for (String c : compensated) {
                        log.append("• <@").append(c).append(">\n");
                    }
                    log.append("\n*(Action Required: Please verify these creators did not post spam/fake listings, and use the `/payout` command to compensate them appropriately!)*");
                }

                EmbedBuilder reportEmbed = new EmbedBuilder()
                        .setColor(new Color(255, 165, 0))
                        .setTitle("✦ CREATOR EVALUATION REPORT ✦")
                        .setDescription(log.toString())
                        .setFooter("AMORA Financial Auditing System", null);
                if (doReset) {
                    reportEmbed.setColor(Color.RED);
                    reportEmbed.addField("DATABASE WIPE REQUESTED ⚠️", "You chose to reset the counters. This action **cannot be undone**. Are you absolutely sure you want to wipe all shop tracking data for the new month?", false);
                    
                    event.replyEmbeds(reportEmbed.build())
                         .addActionRow(
                             Button.danger("confirm_shop_reset", "...CONFIRM WIPE"),
                             Button.secondary("cancel_shop_reset", "  Cancel")
                         ).queue();
                } else {
                    event.replyEmbeds(reportEmbed.build()).queue();
                }
                return;
            }
        }

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

        if (event.getName().equals("activitycheck")) {
            if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("  You do not have clearance to configure templates.").setEphemeral(true).queue();
                return;
            }

            String trigger = event.getOption("trigger").getAsString().trim();
            String reactPhrase = event.getOption("react_phrase").getAsString().trim();
            String goalPhrase = event.getOption("goal_phrase").getAsString().trim();

            db.setBotState("ac_trigger", trigger);
            db.setBotState("ac_react", reactPhrase);
            db.setBotState("ac_goal", goalPhrase);

            EmbedBuilder configEmbed = new EmbedBuilder()
                    .setColor(new Color(0, 250, 154))
                    .setTitle("✦ TEMPLATE CONFIGURATION SAVED ✦")
                    .setDescription("The AMORA tracking engine has been updated to hunt for your new template layout.")
                    .addField("🎯 Required Trigger", "`" + trigger + "`", false)
                    .addField("✨ Reaction Phrase", "`" + reactPhrase + "`", true)
                    .addField("🏆 Goal Phrase", "`" + goalPhrase + "`", true)
                    .setFooter("AMORA Engine Systems", null);

            event.replyEmbeds(configEmbed.build()).queue();
            sendAuditLog(event.getGuild(), "Template Configured",
                    event.getUser().getAsMention() + " updated the Activity Check template to trigger on: `" + trigger + "`",
                    new Color(0, 250, 154));
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
                event.reply("  Your inventory is empty! You have nothing to forge or recycle.")
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
                title = "AM0RA SERVER WEALTH RANKINGS";
                color = new Color(255, 215, 0);
                topList = db.getTopWealth();
            } else if (category.equals("bounties")) {
                title = "AM0RA TOP DIRECTIVE HUNTERS";
                color = new Color(0, 250, 154);
                topList = db.getTopBounties();
            } else if (category.equals("urgent")) {
                title = "AM0RA TOP URGENT RESPONDERS";
                color = new Color(255, 69, 0);
                topList = db.getTopUrgent();
            } else if (category.equals("acwins")) {
                title = "⏱ AM0RA TOP ACTIVE CHECK WINNERS";
                color = new Color(138, 43, 226);
                topList = db.getTopAcWins();
            }else if (category.equals("orders_month")) {
                title = "🛒 AM0RA MOST ACTIVE SHOPS (THIS MONTH)";
                color = new Color(255, 182, 193); 
                topList = db.getTopShopsThisMonth();
            } else if (category.equals("orders_alltime")) {
                title = "👑 AM0RA LEGENDARY SHOPS (ALL TIME)";
                color = new Color(255, 165, 0); 
                topList = db.getTopShopsAllTime();
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
                event.reply("  Invalid target.").setEphemeral(true).queue();
                return;
            }

            String senderInv = db.getInventory(senderId);
            String targetInv = db.getInventory(targetId);

            if (senderInv == null || senderInv.isEmpty()) {
                event.reply("  You don't have assets!").setEphemeral(true).queue();
                return;
            }
            if (targetInv == null || targetInv.isEmpty()) {
                event.reply("  They don't have assets!").setEphemeral(true).queue();
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
                event.reply("  No clearance.").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("forum").getAsChannel().getType() != ChannelType.FORUM) {
                event.reply("  MUST be a Forum Channel!").setEphemeral(true).queue();
                return;
            }
            if (SHOP_FORUM_CHANNEL_ID == null || SHOP_FORUM_CHANNEL_ID.isBlank()) {
                event.reply("  SHOP_FORUM_CHANNEL_ID is not configured.").setEphemeral(true).queue();
                return;
            }
            if (!event.getOption("forum").getAsChannel().getId().equals(SHOP_FORUM_CHANNEL_ID)) {
                event.reply("  **Access Denied:** Wrong channel!").setEphemeral(true).queue();
                return;
            }

            String itemName = event.getOption("name").getAsString();
            String safeName = itemName.length() > 60 ? itemName.substring(0, 60) : itemName;

            if (db.shopItemExists(safeName)) {
                event.reply("  Upload Aborted: This item already exists in the shop.").setEphemeral(true).queue();
                return;
            }

            event.deferReply(true).queue();
            ForumChannel forum = event.getOption("forum").getAsChannel().asForumChannel();
            int price = event.getOption("price").getAsInt();
            if (price < 0) {
                event.reply("The price cannot be negative!").setEphemeral(true).queue();
            return;
            }
            String secretDelivery = event.getOption("delivery").getAsString();
            String description = readDescription(event);
            
            // SMART UPGRADE: Replace the ugly default text with an aesthetic one
            if (description.equals("No description provided.")) {
                description = "An official AM0RA digital asset. _No additional details were provided by the Director._";
            }
            
            String encodedItem = encodeItem(safeName);
            String buttonId = "buy_" + price + "_" + encodedItem;

            MessageCreateBuilder builder = new MessageCreateBuilder();
            List<MessageEmbed> embeds = new ArrayList<>();

            String aestheticDesc = "# ୧ ╰ 𝐀𝐌𝟎𝐑𝐀 POINT 𝐌𝐀𝐑𝐊𝐄𝐓𝐏𝐋𝐀𝐂𝐄 . .ᐟ\n" +
                                   " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n" +
                                   "**" + description + "**\n\n" +
                                   " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                                   "` ~ ୨୧ · ` ✦ 𝐏𝐫𝐢𝐜𝐞 : `" + price + " PTS`\n" +
                                   "` ~ ୨୧ · ` ✦ 𝐒𝐭𝐚𝐭𝐮𝐬 : `In Stock`\n\n" +
                                   "*(Click the button below to instantly deduct Points and receive your asset in DMs!)*";

            EmbedBuilder mainEmbed = new EmbedBuilder()
                    .setColor(new Color(255, 182, 193)) 
                    .setTitle("🛍️ " + itemName.toUpperCase())
                    .setDescription(aestheticDesc)
                    .setFooter("AM0RA Automated Distribution", null);

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
                event.getHook().sendMessage("  Failed to process uploaded files: " + e.getMessage()).queue();
                return;
            }

            builder.addEmbeds(embeds);
            builder.addActionRow(Button.success(buttonId, "🛒 Purchase • " + price + " PTS"));

            forum.createForumPost(itemName, builder.build()).queue(
                    success -> {
                        db.addShopItem(safeName, secretDelivery);
                        event.getHook().sendMessage(" Asset published!").queue();
                        sendShopLog(event.getGuild(), "Asset Published",
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

        // if (event.getName().equals("bounty")) {
        //     if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
        //         event.reply("  You do not have clearance to manage Bounties.").setEphemeral(true).queue();
        //         return;
        //     }

        //     String subCommand = event.getSubcommandName();

        //     if ("post".equals(subCommand)) {
        //         if (event.getOption("forum").getAsChannel().getType() != ChannelType.FORUM) {
        //             event.reply("  MUST be a Forum Channel!").setEphemeral(true).queue();
        //             return;
        //         }
        //         if (STANDARD_BOUNTY_FORUM_ID == null || URGENT_BOUNTY_FORUM_ID == null) {
        //             event.reply("  Bounty forum IDs are not configured.").setEphemeral(true).queue();
        //             return;
        //         }

        //         String selectedForumId = event.getOption("forum").getAsChannel().getId();
        //         if (!selectedForumId.equals(STANDARD_BOUNTY_FORUM_ID) && !selectedForumId.equals(URGENT_BOUNTY_FORUM_ID)) {
        //             event.reply("  **Access Denied:** Must be an official Quest Forum!").setEphemeral(true).queue();
        //             return;
        //         }

        //         ForumChannel forum = event.getOption("forum").getAsChannel().asForumChannel();
        //         String title = event.getOption("title").getAsString();
        //         int reward = event.getOption("reward").getAsInt();
        //         if (reward < 0) {
        //             event.reply("The reward cannot be negative!").setEphemeral(true).queue();
        //         return;
        //         }
        //         String slotsInput = event.getOption("slots").getAsString();
        //         int maxSlots = 0;
        //         try {
        //             maxSlots = Integer.parseInt(slotsInput.trim());
        //         } catch (NumberFormatException ignored) {
        //             maxSlots = 0;
        //         }

        //         String slotDisplay = maxSlots <= 0 ? "Unlimited" : String.valueOf(maxSlots);
        //         String description = readDescription(event);

        //         event.deferReply(true).queue();

        //         MessageCreateBuilder builder = new MessageCreateBuilder();
        //         EmbedBuilder questEmbed = new EmbedBuilder()
        //                 .setColor(new Color(255, 69, 0))
        //                 .setTitle("🎯 DIRECTIVE: " + title.toUpperCase())
        //                 .setDescription(description + "\n\n💰 **Bounty Reward:** `" + reward + " Points` _(Per Person)_\n\n_Press Join below to enter the party!_")
        //                 .addField("👥 Party [0/" + slotDisplay + "]", "None", false)
        //                 .setFooter("AMORA Directive Network • Reward embedded: " + reward, null);

        //         try {
        //             if (event.getOption("image") != null) {
        //                 String imageRef = attachImage(builder, event.getOption("image").getAsAttachment());
        //                 questEmbed.setImage(imageRef);
        //             }
        //         } catch (Exception e) {
        //             event.getHook().sendMessage("  Failed to process uploaded image: " + e.getMessage()).queue();
        //             return;
        //         }

        //         builder.addEmbeds(questEmbed.build());
        //         builder.addActionRow(
        //                 Button.success("bjoin_button", "✋ Join Quest"),
        //                 Button.danger("bleave_button", "🛑 Leave Quest")
        //         );

        //         forum.createForumPost("🎯 " + title + " [" + reward + " PTS]", builder.build()).queue(
        //                 success -> {
        //                     event.getHook().sendMessage(" Dynamic Party Bounty posted!").queue();
        //                     sendAuditLog(event.getGuild(), "Bounty Posted",
        //                             event.getUser().getAsMention() + " posted Directive: **" + title + "** for `"
        //                                     + reward + " Points`.", new Color(255, 69, 0));
        //                 },
        //                 error -> {
        //                     error.printStackTrace();
        //                     event.getHook().sendMessage(
        //                                     "⚠️ Discord returned an upload/network error after submission. Check the quest forum — the post may already exist.")
        //                             .queue();
        //                 }
        //         );
        //         return;
        //     }

        //     if ("kick".equals(subCommand)) {
        //         if (!event.getChannel().getType().isThread()) {
        //             event.reply("  Run this inside the Quest Thread!").setEphemeral(true).queue();
        //             return;
        //         }

        //         User target = event.getOption("target").getAsUser();
        //         ThreadChannel thread = event.getChannel().asThreadChannel();

        //         event.reply("🔄 Removing " + target.getName() + " from the party...").setEphemeral(true).queue(replyHook ->
        //                 thread.retrieveStartMessage().queue(startMsg -> {
        //                     MessageEmbed oldEmbed = startMsg.getEmbeds().get(0);
        //                     EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);
        //                     String[] partyField = getPartyField(oldEmbed);
        //                     int fieldIndex = getPartyFieldIndex(oldEmbed);

        //                     if (partyField == null || fieldIndex == -1) {
        //                         replyHook.editOriginal("  Party data not found.").queue();
        //                         return;
        //                     }

        //                     String partyName = partyField[0];
        //                     String partyValue = partyField[1];
        //                     String userMention = target.getAsMention();

        //                     if (!partyValue.contains(userMention)) {
        //                         replyHook.editOriginal("  " + userMention + " is not currently in the party!").queue();
        //                         return;
        //                     }

        //                     int current = parsePartyCurrent(partyName);
        //                     String maxStr = parsePartyMax(partyName);

        //                     partyValue = partyValue.replace(userMention + "\n", "")
        //                             .replace("\n" + userMention, "")
        //                             .replace(userMention, "");

        //                     if (partyValue.trim().isEmpty()) {
        //                         partyValue = "None";
        //                     }

        //                     current--;

        //                     newEmbed.getFields().remove(fieldIndex);
        //                     newEmbed.addField("👥 Party [" + current + "/" + maxStr + "]", partyValue, false);

        //                     if (current == 0) {
        //                         newEmbed.setColor(new Color(255, 69, 0));
        //                     }

        //                     startMsg.editMessageEmbeds(newEmbed.build())
        //                             .setActionRow(
        //                                     Button.success("bjoin_button", "✋ Join Quest"),
        //                                     Button.danger("bleave_button", "🛑 Leave Quest")
        //                             )
        //                             .queue();

        //                     replyHook.editOriginal(" Successfully kicked " + userMention + " from the party.").queue();
        //                     thread.sendMessage("⚠️ Admin Action: " + userMention + " has been removed from the party by a Director.").queue();
        //                     sendAuditLog(event.getGuild(), "Bounty Kick",
        //                             event.getUser().getAsMention() + " removed " + userMention + " from a party in thread `"
        //                                     + thread.getId() + "`.", Color.ORANGE);
        //                 }, error -> replyHook.editOriginal("  Error fetching the starting message.").queue()));
        //         return;
        //     }

        //     if ("cancel".equals(subCommand)) {
        //         if (!event.getChannel().getType().isThread()) {
        //             event.reply("  Run this inside the Quest Thread!").setEphemeral(true).queue();
        //             return;
        //         }

        //         ThreadChannel thread = event.getChannel().asThreadChannel();
        //         event.reply("🔄 Aborting directive...").queue(replyHook ->
        //                 thread.retrieveStartMessage().queue(startMsg -> {
        //                     MessageEmbed oldEmbed = startMsg.getEmbeds().get(0);
        //                     EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);
        //                     newEmbed.setColor(Color.RED);

        //                     int partyFieldIndex = getPartyFieldIndex(oldEmbed);
        //                     if (partyFieldIndex != -1) {
        //                         newEmbed.getFields().remove(partyFieldIndex);
        //                     }

        //                     newEmbed.addField("  DIRECTIVE CANCELLED",
        //                             "This quest was aborted by " + event.getUser().getAsMention() + ". No points were awarded.", false);

        //                     startMsg.editMessageEmbeds(newEmbed.build()).setComponents().queue(done -> {
        //                         replyHook.editOriginalEmbeds(new EmbedBuilder()
        //                                 .setColor(Color.RED)
        //                                 .setTitle("DIRECTIVE ABORTED")
        //                                 .setDescription("Quest cancelled and locked.")
        //                                 .build()).setContent("").queue(done2 ->
        //                                 thread.getManager().setLocked(true).setArchived(true).queue());

        //                         sendAuditLog(event.getGuild(), "Bounty Cancelled",
        //                                 event.getUser().getAsMention() + " aborted the quest in thread `"
        //                                         + thread.getId() + "`.", Color.RED);
        //                     });
        //                 }, error -> replyHook.editOriginal("  Error fetching the starting message.").queue()));
        //         return;
        //     }

        //     if ("complete".equals(subCommand)) {
        //         if (!event.getChannel().getType().isThread()) {
        //             event.reply("  Run this inside the Quest Thread!").setEphemeral(true).queue();
        //             return;
        //         }

        //         ThreadChannel thread = event.getChannel().asThreadChannel();
        //         boolean isUrgent = URGENT_BOUNTY_FORUM_ID != null
        //                 && thread.getParentChannel().getId().equals(URGENT_BOUNTY_FORUM_ID);
        //         String commandExecutorId = event.getUser().getId();

        //         event.reply("🔄 Processing party mass-payout and logging stats...").queue(replyHook ->
        //                 thread.retrieveStartMessage().queue(startMsg -> {
        //                     MessageEmbed oldEmbed = startMsg.getEmbeds().get(0);
        //                     String footerText = oldEmbed.getFooter() != null ? oldEmbed.getFooter().getText() : "0";
        //                     int rewardAmount = Integer.parseInt(footerText.replaceAll("[^0-9]", ""));
        //                     String[] partyField = getPartyField(oldEmbed);
        //                     int partyFieldIndex = getPartyFieldIndex(oldEmbed);
        //                     String partyData = partyField == null ? "None" : partyField[1];

        //                     if (partyData.equals("None") || partyData.isEmpty()) {
        //                         replyHook.editOriginal("  Cannot complete. The party is empty!").queue();
        //                         return;
        //                     }

        //                     List<String> excludedIds = parseMentionIds(event.getOption("exclude") != null
        //                             ? event.getOption("exclude").getAsString() : "");
        //                     List<String> userIds = parseMentionIds(partyData);
        //                     boolean selfApprove = userIds.contains(commandExecutorId) && !excludedIds.contains(commandExecutorId);
        //                     StringBuilder payoutLog = new StringBuilder();

        //                     for (String uid : userIds) {
        //                         if (excludedIds.contains(uid)) {
        //                             payoutLog.append("• <@").append(uid).append("> was excluded from the payout.\n");
        //                             continue;
        //                         }

        //                         int currentPoints = db.getPoints(uid);
        //                         db.updatePoints(uid, currentPoints + rewardAmount);
        //                         db.incrementBountyStats(uid, isUrgent);
        //                         payoutLog.append("• <@").append(uid).append("> received `")
        //                                 .append(rewardAmount).append(" PTS`\n");
        //                     }

        //                     EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);
        //                     newEmbed.setColor(selfApprove ? Color.ORANGE : Color.GREEN);

        //                     if (partyFieldIndex != -1) {
        //                         newEmbed.getFields().remove(partyFieldIndex);
        //                     }

        //                     newEmbed.addField(" QUEST CLEARED",
        //                             "Successfully completed by the party!\n\n" + payoutLog, false);

        //                     if (selfApprove) {
        //                         newEmbed.addField("⚠️ OVERRIDE LOGGED",
        //                                 event.getUser().getAsMention() + " authorized a payout that included themselves.", false);
        //                     }

        //                     startMsg.editMessageEmbeds(newEmbed.build()).setComponents().queue(done -> {
        //                         EmbedBuilder receiptEmbed = new EmbedBuilder()
        //                                 .setColor(selfApprove ? Color.ORANGE : Color.GREEN)
        //                                 .setTitle(selfApprove ? "DIRECTIVE CLEARED WITH WARNING" : "DIRECTIVE CLEARED")
        //                                 .setDescription("All party members have been paid and stats updated. Thread locking and archiving...");

        //                         replyHook.editOriginalEmbeds(receiptEmbed.build()).setContent("").queue(done2 ->
        //                                 thread.getManager().setLocked(true).setArchived(true).queue());

        //                         if (selfApprove) {
        //                             sendAuditLog(event.getGuild(), "SUSPICIOUS PAYOUT",
        //                                     event.getUser().getAsMention() + " self-approved a bounty payout in thread `"
        //                                             + thread.getId() + "` for `" + rewardAmount + " Points`.", Color.RED);
        //                         } else {
        //                             sendAuditLog(event.getGuild(), "Bounty Cleared",
        //                                     event.getUser().getAsMention() + " cleared the quest in thread `"
        //                                             + thread.getId() + "`. Paid out `" + rewardAmount + " Points`.",
        //                                     Color.GREEN);
        //                         }
        //                     });
        //                 }, error -> replyHook.editOriginal("  Error fetching the starting message.").queue()));
        //         return;
        //     }

        //     return;
        // }

        if (event.getName().equals("addsparks")) {
            if (!requireAnyConfiguredRole(event, ADDSPARKS_ROLE_IDS, "ADDSPARKS_ROLE_IDS")) {
                return;
            }

            User targetUser = event.getOption("target").getAsUser();
            int amount = event.getOption("amount").getAsInt();

            if (amount <= 0) {
                event.reply("  Amount must be greater than 0.")
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
                event.reply("  Amount must be greater than 0.")
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
                event.reply("  You do not have clearance to award performance Sparks.").setEphemeral(true).queue();
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
                event.reply("  Missing subcommand. Use `/song add`, `/song importplaylist`, `/song list`, `/song suggest`, or `/song remove`.")
                        .setEphemeral(true).queue();
                return;
            }

            if (subcommand.equals("add")) {
                String title = event.getOption("title").getAsString().trim();
                String artist = event.getOption("artist").getAsString().trim();
                String link = normalizeSongLink(event.getOption("link").getAsString().trim());

                if (title.isBlank() || artist.isBlank() || link.isBlank()) {
                    event.reply("  Title, artist, and link are required.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (title.length() > 120 || artist.length() > 120 || link.length() > 500) {
                    event.reply("  One or more fields are too long.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (!isSupportedSongLink(link)) {
                    event.reply("  Please submit a valid Spotify track or YouTube song link.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (db.songLinkExists(link)) {
                    event.reply("  That exact song link is already in the AMORA pool.")
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
                    event.reply("  Failed to save the song suggestion.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.replyEmbeds(
                        buildSongEmbed(created, "✦ SONG ADDED TO THE AMORA POOL ✦", "AMORA Daily Music Pool").build()
                ).setEphemeral(true).queue();
                return;
            }

            if (subcommand.equals("importplaylist")) {
                if (!isMusicStaff(event)) {
                    event.reply("  Only AMORA Staff can mass-import playlists.")
                            .setEphemeral(true).queue();
                    return;
                }
                
                String playlistLink = event.getOption("link").getAsString().trim();

                if (playlistLink.isBlank()) {
                    event.reply("  Playlist link is required.").setEphemeral(true).queue();
                    return;
                }

                if (!isYouTubePlaylistLink(playlistLink)) {
                    event.reply("  Please provide a valid public YouTube playlist link or Playlist ID.").setEphemeral(true).queue();
                    return;
                }

                event.deferReply(true).queue(hook -> {
                    scheduler.execute(() -> {
                        try {
                            List<YouTubePlaylistImporter.ImportedSong> importedSongs =
                                    YouTubePlaylistImporter.importPlaylist(playlistLink);

                            if (importedSongs.isEmpty()) {
                                hook.editOriginal("  No usable songs were found in that playlist.").queue();
                                return;
                            }

                            int skippedExisting = 0;
                            int skippedInvalid = 0;
                            Set<String> seenThisImport = new HashSet<>();
                            
                            List<String> bulkLinks = new ArrayList<>();
                            List<String> bulkTitles = new ArrayList<>();
                            List<String> bulkArtists = new ArrayList<>();
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

                                bulkLinks.add(normalizedLink);
                                bulkTitles.add(imported.title());
                                bulkArtists.add(imported.artist());

                                if (preview.length() < 900) {
                                    preview.append("✦ **").append(truncateText(imported.title(), 40)).append("**")
                                            .append(" — ").append(truncateText(imported.artist(), 30))
                                            .append("\n");
                                }
                            }

                            int added = 0;
                            if (!bulkLinks.isEmpty()) {
                                added = db.bulkAddSongSuggestions(userId, bulkLinks, bulkTitles, bulkArtists, "YouTube");
                            }

                            EmbedBuilder resultEmbed = new EmbedBuilder()
                                    .setColor(new Color(255, 105, 180))
                                    .setTitle("✦ PLAYLIST IMPORT COMPLETE ✦")
                                    .setDescription(
                                            "🎵 Playlist scan finished.\n\n" +
                                            " Added: `" + added + "`\n" +
                                            "♻️ Already in pool: `" + skippedExisting + "`\n" +
                                            "⚠️ Skipped/invalid: `" + skippedInvalid + "`"
                                    )
                                    .setFooter("AMORA Music Importer", null);

                            if (preview.length() > 0) {
                                resultEmbed.addField("Imported Songs Preview", preview.toString(), false);
                            }

                            hook.editOriginalEmbeds(resultEmbed.build()).setContent("").queue();

                        } catch (Throwable t) { 
                            t.printStackTrace();
                            String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                            if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
                                errorMsg = t.getCause().getMessage() != null ? t.getCause().getMessage() : t.getCause().getClass().getSimpleName();
                            }
                            hook.editOriginal("  Failed to import playlist: " + errorMsg).queue();
                        }
                    });
                });
                return;
            }
            if (subcommand.equals("remove")) {
                int songId = event.getOption("id").getAsInt();
                DatabaseManager.SongSuggestionRecord song = db.getSongSuggestionById(songId);

                if (song == null || !song.active) {
                    event.reply("  That song ID does not exist in the active pool.")
                            .setEphemeral(true).queue();
                    return;
                }

                boolean isOwner = userId.equals(song.addedBy);
                boolean isStaff = isMusicStaff(event);

                if (!isOwner && !isStaff) {
                    event.reply("  You can only remove songs you added yourself unless you are AMORA Staff.")
                            .setEphemeral(true).queue();
                    return;
                }

                boolean removed = db.deactivateSongSuggestion(songId);
                if (!removed) {
                    event.reply("  Failed to remove that song.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.reply(" Removed **" + song.title + "** by **" + song.artist + "** from the AMORA pool.")
                        .setEphemeral(true).queue();
                return;
            }

            if (subcommand.equals("list")) {
                if (!isMusicStaff(event)) {
                    event.reply("  Only AMORA Staff can view the full song pool directory.")
                            .setEphemeral(true).queue();
                    return;
                }
                
                List<DatabaseManager.SongSuggestionRecord> songs = db.getRecentSongSuggestions(5000);

                if (songs.isEmpty()) {
                    event.reply("  The AMORA song pool is empty right now. Add one with `/song add`.")
                            .setEphemeral(true).queue();
                    return;
                }

                StringBuilder fileContent = new StringBuilder();
                fileContent.append("✦ AMORA COMPLETE SONG POOL DIRECTORY ✦\n");
                fileContent.append("Total Active Songs: ").append(songs.size()).append("\n");
                fileContent.append("=========================================\n\n");

                for (DatabaseManager.SongSuggestionRecord song : songs) {
                    fileContent.append("[ ID: #").append(song.songId).append(" ]\n");
                    fileContent.append("Title   : ").append(song.title).append("\n");
                    fileContent.append("Artist  : ").append(song.artist).append("\n");
                    fileContent.append("Link    : ").append(song.link).append("\n");
                    fileContent.append("Added By: ").append(song.addedBy).append("\n");
                    fileContent.append("-----------------------------------------\n");
                }

                byte[] fileBytes = fileContent.toString().getBytes(StandardCharsets.UTF_8);
                FileUpload upload = FileUpload.fromData(fileBytes, "AMORA_Song_Directory.txt");

                event.reply("📂 **AMORA Song Directory**\nBecause the database is so large, I have compiled the entire active song pool into this text file. \n\n*Tip: Open it and use `Ctrl+F` to instantly search for the song ID you need to remove!*")
                        .addFiles(upload)
                        .setEphemeral(true) 
                        .queue();
                return;
            }

            if (subcommand.equals("suggest")) {
                DatabaseManager.SongSuggestionRecord song = db.getRandomActiveSongSuggestion();

                if (song == null) {
                    event.reply("  There are no active song suggestions yet.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.replyEmbeds(
                        buildSongEmbed(song, "✦ RANDOM AMORA SONG PICK ✦", "AMORA Music Recommendation").build()
                ).queue();
                return;
            }

            if (subcommand.equals("postnow")) {
                if (!isMusicStaff(event)) {
                    event.reply("  Only AMORA Staff can force-post the daily song.")
                            .setEphemeral(true).queue();
                    return;
                }

                if (DAILY_SONG_CHANNEL_ID == null || DAILY_SONG_CHANNEL_ID.isBlank()) {
                    event.reply("  DAILY_SONG_CHANNEL_ID is not configured.")
                            .setEphemeral(true).queue();
                    return;
                }

                boolean posted = App.postSongRecommendation(event.getJDA(), false);
                if (!posted) {
                    event.reply("  Could not post a song right now. Check the channel ID or make sure the pool has songs.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.reply(" Song recommendation posted in <#" + DAILY_SONG_CHANNEL_ID + ">.")
                        .setEphemeral(true).queue();
                return;
            }

            event.reply("  Unknown song subcommand.")
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
                event.reply("  This trade setup has expired.").setEphemeral(true).queue();
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
                event.reply("  This is not your forge session!").setEphemeral(true).queue();
                return;
            }

            String selectedIngredient = event.getValues().get(0);
            if (selectedIngredient.equals("none")) {
                event.deferEdit().queue();
                return;
            }

            String currentInv = db.getInventory(ownerId);
            if (getItemCount(currentInv, selectedIngredient) < 3) {
                event.reply("  You no longer have 3 of these to craft!").setEphemeral(true).queue();
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
                event.reply("  Not your session!").setEphemeral(true).queue();
                return;
            }

            String selectedReward = event.getValues().get(0);
            String ingredient = db.getPendingForgeIngredient(ownerId);

            if (ingredient == null) {
                event.reply("  Forge session expired. Try again.").setEphemeral(true).queue();
                return;
            }

            synchronized (this) {
                String currentInv = db.getInventory(ownerId);
                if (getItemCount(currentInv, ingredient) < 3) {
                    event.reply("  You no longer have 3x of the ingredient!").setEphemeral(true).queue();
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
                event.reply("  This is not your forge session!").setEphemeral(true).queue();
                return;
            }

            String selected = event.getValues().get(0);

            synchronized (this) {
                String currentInv = db.getInventory(ownerId);
                if (getItemCount(currentInv, selected) < 1) {
                    event.reply("  You do not have this item anymore!").setEphemeral(true).queue();
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
        
        if (componentId.startsWith("order_start_")) {
            String creatorId = componentId.substring("order_start_".length());
            User buyer = event.getUser();

            if (creatorId.equals(buyer.getId())) {
                event.reply(MIKU_SAD + " You cannot order items from yourself!").setEphemeral(true).queue();
                return;
            }

            String orderChannelId = System.getenv("ORDER_CHANNEL_ID");
            if (orderChannelId == null) {
                event.reply(MIKU_SAD + " The server's order channel is not configured.").setEphemeral(true).queue();
                return;
            }

            TextChannel orderChannel = event.getJDA().getTextChannelById(orderChannelId);
            if (orderChannel == null) {
                event.reply("Cannot find the official order channel.").setEphemeral(true).queue();
                return;
            }

            event.getJDA().retrieveUserById(creatorId).queue(creator -> {
                java.util.Optional<ThreadChannel> activeThread = orderChannel.getThreadChannels().stream()
                        .filter(t -> t.getName().contains(buyer.getName() + " ➔ " + creator.getName()))
                        .findFirst();

                if (activeThread.isPresent()) {
                    event.reply(MIKU_SAD + " **Hold on!** You already have an open transaction with **" + creator.getName() + "** in " + activeThread.get().getAsMention() + "!\n\n🛒 **Want to buy this too?**\nJust drop the link to this outfit in that thread and use the `➕ Add Item` button to buy them all at once!").setEphemeral(true).queue();
                    return;
                }

                long buyerTotalActiveOrders = orderChannel.getThreadChannels().stream()
                .filter(t -> t.getThreadMembers().stream().anyMatch(m -> m.getId().equals(buyer.getId())))
                .count();

                if (buyerTotalActiveOrders >= 3) {
                    event.reply(MIKU_SAD + " **Cart Full!** You currently have 3 active orders open. Please finish or cancel an existing transaction before adding more items to your cart!").setEphemeral(true).queue();
                    return;
                }

                long creatorActiveOrders = orderChannel.getThreadChannels().stream()
                        .filter(t -> t.getName().contains("➔ " + creator.getName()))
                        .count();

                if (creatorActiveOrders >= 5) {
                    event.reply(XB_CUTE + " **Queue Full!** " + creator.getName() + " currently has 5 active orders. Please wait for them to finish some commissions before ordering from them!").setEphemeral(true).queue();
                    return;
                }

                ThreadChannel shopThread = event.getChannel().asThreadChannel();
                shopThread.retrieveStartMessage().queue(startMsg -> {
                    String itemDescription = startMsg.getContentRaw();
                    if (itemDescription.length() > 300) {
                        itemDescription = itemDescription.substring(0, 300) + "..."; 
                    }
                    if (itemDescription.isBlank()) itemDescription = "_Visual asset (See image below)_";

                    String messageLink = startMsg.getJumpUrl();

                    EmbedBuilder orderEmbed = new EmbedBuilder()
                            .setColor(new Color(255, 182, 193))
                            .setTitle("✦ AUTOMATED COMMISSION ORDER ✦")
                            .setDescription(
                                    buyer.getAsMention() + " just placed a seamless order!\n\n" +
                                    CINNA_HIDE + " **Item Requested:**\n" +
                                    "> " + itemDescription.replace("\n", "\n> ") + "\n\n" +
                                    "🔗 [**Click here to view the original shop post**](" + messageLink + ")\n\n" +
                                    "*(Creator: Accept this order below to log your sale!)*"
                            )
                            .setThumbnail(buyer.getEffectiveAvatarUrl())
                            .setFooter("AMORA Smart UI Order System", null);

                    if (!startMsg.getAttachments().isEmpty()) {
                        Attachment image = startMsg.getAttachments().get(0);
                        if (image.isImage()) {
                            orderEmbed.setImage(image.getUrl());
                        }
                    }

                    event.reply(" **Order placed!** I am setting up a private transaction thread for you in " + orderChannel.getAsMention() + ". Please do the Orders inside of this Private Thread!!").setEphemeral(true).queue();

                    String buyerName = buyer.getName();
                    String creatorName = creator.getName();
                    
                    String generatingUI = "# ------ᜊ  ⌒⌒ ⠀𓈒 𝐍𝐞𝐰 𝐎𝐫𝐝𝐞𝐫 🛒 ₊ ⊹---------\n" +
                                          ".⠀.   ᘛ   ˚⠀𝐀𝐌𝟎𝐑𝐀 **𝖲𝖬𝖠𝖱𝖳 𝖴𝖨** ! ˚\n\n" +
                                          "⋆ ˚｡⋆୨୧˚\n\n" +
                                          "۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪\n" +
                                          "ྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌ\n" +
                                          "֊   ᎔   ᎔   𑣩𑣨  ᎔   ᎔    ᎔   ᎔\n\n" +
                                          "_ _  ✩   𓏼    ׅ    ۟ 𐐂 Commission Request 𐐚 ✧.\n" +
                                          "_ _   ꒰ ଲ ꒱  ✦ **From:** " + buyerName + "\n" +
                                          "_ _   ꒰ Ꮼ ꒱  ✦ **For:** " + creatorName + "\n" +
                                          "_ _   ꒰ ⌾ ꒱  ✦ **Status:** ⏳ *Weaving the digital threads...*";

                    orderChannel.sendMessage(generatingUI).queue(mainMessage -> {
                        String shortId = UUID.randomUUID().toString().substring(0, 4);
                        orderChannel.createThreadChannel("⏳ " + buyerName + " ➔ " + creatorName + " [" + shortId + "]", true).queue(thread -> {
                             
                             String readyUI = "# ------ᜊ  ⌒⌒ ⠀𓈒 𝐍𝐞𝐰 𝐎𝐫𝐝𝐞𝐫 🛒 ₊ ⊹---------\n" +
                                              ".⠀.   ᘛ   ˚⠀𝐀𝐌𝟎𝐑𝐀 **𝖲𝖬𝖠𝖱𝖳 𝖴𝖨** ! ˚\n\n" +
                                              "⋆ ˚｡⋆୨୧˚\n\n" +
                                              "۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪   ִ    ۪   ‌   ࣪\n" +
                                              "ྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌྌ\n" +
                                              "֊   ᎔   ᎔   𑣩𑣨  ᎔   ᎔    ᎔   ᎔\n\n" +
                                              "_ _  ✩   𓏼    ׅ    ۟ 𐐂 Commission Request 𐐚 ✧.\n" +
                                              "_ _   ꒰ ଲ ꒱  ✦ **From:** " + buyerName + "\n" +
                                              "_ _   ꒰ Ꮼ ꒱  ✦ **For:** " + creatorName + "\n" +
                                              "_ _   ꒰ ⌾ ꒱  ✦ **Status:** 🔒 *Transaction Room Secured!*\n\n" +
                                              "<a:Saur_Heart:1525689248391368796>  _ _  ᨳ   𓏼    ׅ    ۟ 𐐂 Enter your Private Thread here: 𐐚 ೃ⁀➷\n" +
                                              "_ _   " + thread.getAsMention();
                             
                             mainMessage.editMessage(readyUI).queue();
                             
                             thread.addThreadMember(creator).queue();
                             thread.addThreadMember(buyer).queue();

                             thread.sendMessage(creator.getAsMention() + " ✦ " + buyer.getAsMention() + "\nHere is your private transaction room 🔒! Please share all details and payment proofs here.")
                                   .addEmbeds(orderEmbed.build())
                                   .addActionRow(
                                       Button.success("orderaccept_" + creator.getId() + "_" + buyer.getId(), " Accept Order"),
                                       Button.danger("orderdecline_" + creator.getId() + "_" + buyer.getId(), "  Decline")
                                   ).queue();
                        });
                    });
                });
            }, error -> {
                event.reply(MIKU_SAD + " Could not find the creator.").setEphemeral(true).queue();
            });
            return;
        }
        
        if (componentId.equals("confirm_shop_reset")) {
            if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("  Director clearance required.").setEphemeral(true).queue();
                return;
            }
            
            db.generateCompensationReport(true); 
            
            EmbedBuilder successEmbed = new EmbedBuilder(event.getMessage().getEmbeds().get(0));
            successEmbed.setColor(new Color(0, 250, 154));
            successEmbed.getFields().clear(); 
            successEmbed.addField("DATABASE WIPED", "All shop tracking counters have been permanently reset to `0` for the new cycle.", false);
            
            event.editMessageEmbeds(successEmbed.build()).setComponents().queue();
            sendShopLog(event.getGuild(), "Shop Counters Reset", event.getUser().getAsMention() + " bypassed the safety lock and performed a global shop counter wipe.", Color.RED);
            return;
        }

        if (componentId.equals("cancel_shop_reset")) {
            if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("  Director clearance required.").setEphemeral(true).queue();
                return;
            }
            
            EmbedBuilder cancelEmbed = new EmbedBuilder(event.getMessage().getEmbeds().get(0));
            cancelEmbed.setColor(new Color(255, 165, 0));
            cancelEmbed.getFields().clear(); 
            cancelEmbed.addField("🛑 RESET CANCELLED", "The wipe was aborted. Shop tracking will continue normally.", false);
            
            event.editMessageEmbeds(cancelEmbed.build()).setComponents().queue();
            return;
        }

        if (componentId.startsWith("shopping_")) {
            String[] parts = componentId.split("_");
            if (parts.length < 3) return;
            String roleId = parts[1];
            String creatorId = parts[2];

            if (!event.getUser().getId().equals(creatorId)) {
                event.reply("  Only the shop owner can ping for this drop!").setEphemeral(true).queue();
                return;
            }

            String menuMsgId = event.getMessageId();

            event.getChannel().sendMessage("<@&" + roleId + "> ✦ **New Shop Drop!**\n" + event.getUser().getAsMention() + " just posted a new item above! ")
                 .addActionRow(Button.danger("deleteping_" + creatorId + "_" + roleId + "_" + menuMsgId, "🗑️ Undo Ping"))
                 .queue();

            List<Button> newButtons = new ArrayList<>();
            for (Button b : event.getMessage().getButtons()) {
                if (b.getId() != null && b.getId().equals(componentId)) {
                    newButtons.add(b.asDisabled().withLabel("✅ Sent"));
                } else {
                    newButtons.add(b);
                }
            }
            
            event.editMessage(event.getUser().getAsMention() + " ✦ **Ping sent!** (You can select another or click '✅ Done / Close' to dismiss)")
                 .setActionRow(newButtons)
                 .queue();
            return;
        }

        if (componentId.startsWith("shopnoping_")) {
            String creatorId = componentId.substring("shopnoping_".length());

            if (!event.getUser().getId().equals(creatorId)) {
                event.reply("  Only the shop owner can make this choice!").setEphemeral(true).queue();
                return;
            }

            event.editMessage("✅ **Ping session closed. Matcha luvs u <3**").setComponents().queue();
            
            event.getMessage().delete().queueAfter(5, java.util.concurrent.TimeUnit.SECONDS);

            event.getChannel().getHistory().retrievePast(20).queue(messages -> {
                for (net.dv8tion.jda.api.entities.Message msg : messages) {
                    if (msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                        if (!msg.getButtons().isEmpty()) {
                            Button b = msg.getButtons().get(0);
                            if (b.getId() != null && b.getId().startsWith("deleteping_" + creatorId + "_")) {
                                msg.editMessageComponents().queue(); 
                            }
                        }
                    }
                }
            });
            return;
        }

        if (componentId.startsWith("deleteping_")) {
            String[] parts = componentId.split("_");
            if (parts.length < 4) {
                event.reply("  Invalid undo payload.").setEphemeral(true).queue();
                return;
            }
            String creatorId = parts[1];
            String roleId = parts[2];
            String menuMsgId = parts[3];

            if (!event.getUser().getId().equals(creatorId)) {
                event.reply("  Only the shop owner can delete this ping!").setEphemeral(true).queue();
                return;
            }

            event.getMessage().delete().queue();

            event.getChannel().retrieveMessageById(menuMsgId).queue(menuMsg -> {
                List<Button> newButtons = new ArrayList<>();
                boolean foundButton = false;
                String targetButtonId = "shopping_" + roleId + "_" + creatorId;
                
                for (Button b : menuMsg.getButtons()) {
                    if (b.getId() != null && b.getId().equals(targetButtonId)) {
                        
                        String label = "📢 Ping";
                        if (roleId.equals(System.getenv("PING_OUTFITS"))) label = "👗 Ping Outfits";
                        else if (roleId.equals(System.getenv("PING_LYRICS"))) label = "📝 Ping Lyrics";
                        else if (roleId.equals(System.getenv("PING_FACES"))) label = "🎭 Ping Faces";
                        else if (roleId.equals(System.getenv("PING_BUILDS"))) label = "🛠️ Ping Builds"; 
                        
                        newButtons.add(Button.primary(targetButtonId, label));  
                        foundButton = true;
                    } else {
                        newButtons.add(b);
                    }
                }
                
                if (foundButton && !newButtons.isEmpty()) {
                    menuMsg.editMessageComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(newButtons)).queue();
                    event.reply("False ping successfully removed, and the option was restored in the menu!").setEphemeral(true).queue();
                } else {
                     event.reply("False ping successfully removed! (The menu was already closed)").setEphemeral(true).queue();
                }
            }, error -> {
                event.reply("False ping successfully removed!").setEphemeral(true).queue();
            });
            return;
        }

        if (componentId.startsWith("orderaccept_")) {
            String[] parts = componentId.split("_");
            String creatorId = parts[1];
            String buyerId = parts[2];
            
            if (!event.getUser().getId().equals(creatorId)) {
                event.reply("  Only the requested creator can accept this order!").setEphemeral(true).queue();
                return;
            }

            EmbedBuilder originalEmbed = new EmbedBuilder(event.getMessage().getEmbeds().get(0));
            originalEmbed.setColor(new Color(255, 165, 0)); 
            originalEmbed.addField("⏳ STATUS: ACCEPTED", "The creator accepted this request! See the active cart below to finish the transaction.", false);
            
            event.editMessageEmbeds(originalEmbed.build())
                 .setComponents(java.util.Collections.emptyList()) 
                 .queue(); 
                 
            event.getChannel().sendMessage("🎉 <@" + buyerId + "> Your order was accepted by " + event.getUser().getAsMention() + "!\n\n" +
                                           "🛍️ **Want to add more items to this order?**\n" +
                                           "Nub Matcha says: You don't need a new ticket! Just drop the other items here and click the `➕ Add Item` button on the cart to update your total! >p<").queue();
            
            event.getChannel().asThreadChannel().getManager().setName(event.getChannel().getName().replace("⏳", "🔒")).queue();

            EmbedBuilder miniCart = new EmbedBuilder()
                    .setColor(new Color(255, 165, 0))
                    .setDescription("🛒 **CART SIZE: 1**\n_Both parties must confirm to log this sale!_");

            event.getChannel().sendMessageEmbeds(miniCart.build())
                 .addActionRow(
                     Button.primary("buyerconfirm_" + creatorId + "_" + buyerId, "🛍️ Buyer Confirm"),
                     Button.primary("sellerconfirm_" + creatorId + "_" + buyerId, "🤝 Seller Confirm"),
                     Button.secondary("additem_" + creatorId + "_" + buyerId, "➕ Add Item"),
                     Button.secondary("remitem_" + creatorId + "_" + buyerId, "➖ Remove"),
                     Button.danger("ordercancel_" + creatorId + "_" + buyerId, " Cancel Order")
                 ).queue();

            return;
        }

        if (componentId.startsWith("additem_") || componentId.startsWith("remitem_")) {
            String[] parts = componentId.split("_");
            String action = parts[0];
            String creatorId = parts[1];
            String buyerId = parts[2];

            if (!event.getUser().getId().equals(creatorId) && !event.getUser().getId().equals(buyerId)) {
                event.reply("❌ Only the buyer or creator can edit the cart!").setEphemeral(true).queue();
                return;
            }

            MessageEmbed embed = event.getMessage().getEmbeds().get(0);
            EmbedBuilder newEmbed = new EmbedBuilder(embed);
            
            String desc = embed.getDescription() != null ? embed.getDescription() : "";
            int currentItems = 1;
            
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\*\\*CART SIZE: (\\d+)\\*\\*").matcher(desc);
                if (m.find()) {
                    currentItems = Integer.parseInt(m.group(1));
                }
            } catch (Exception ignored) {}

            if (action.equals("additem")) {
                currentItems++;
            } else if (action.equals("remitem")) {
                if (currentItems > 1) {
                    currentItems--;
                } else {
                    event.reply("❌ The cart must have at least 1 item!").setEphemeral(true).queue();
                    return;
                }
            }
            
            newEmbed.setDescription("🛒 **CART SIZE: " + currentItems + "**\n_Both parties must confirm to log this sale!_");
            newEmbed.clearFields(); 

            boolean wasReset = false;
            List<Button> updatedButtons = new ArrayList<>();
            
            for (Button b : event.getMessage().getButtons()) {
                if (b.getId() != null && b.getId().startsWith("buyerconfirm_") && b.getLabel() != null && b.getLabel().contains("Confirmed")) {
                    updatedButtons.add(Button.primary(b.getId(), "🛍️ Buyer Confirm"));
                    wasReset = true;
                } 
                else if (b.getId() != null && b.getId().startsWith("sellerconfirm_") && b.getLabel() != null && b.getLabel().contains("Confirmed")) {
                    updatedButtons.add(Button.primary(b.getId(), "🤝 Seller Confirm"));
                    wasReset = true;
                } 
                else {
                    updatedButtons.add(b);
                }
            }
            
            event.editMessageEmbeds(newEmbed.build())
                 .setComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(updatedButtons))
                 .queue();
                 
            if (wasReset) {
                event.getChannel().sendMessage("⚠️ " + event.getUser().getAsMention() + " adjusted the cart to **" + currentItems + "** items! Confirmations have been reset. Both parties must re-confirm the new amount!").queue();
            }
            return;
        }

        if (componentId.startsWith("buyerconfirm_") || componentId.startsWith("sellerconfirm_")) {
            String[] parts = componentId.split("_");
            String type = parts[0];
            String creatorId = parts[1];
            String buyerId = parts[2];

            if (type.equals("buyerconfirm") && !event.getUser().getId().equals(buyerId)) {
                event.reply("  Only the BUYER (<@" + buyerId + ">) can click this button!").setEphemeral(true).queue();
                return;
            }
            if (type.equals("sellerconfirm") && !event.getUser().getId().equals(creatorId)) {
                event.reply("  Only the SELLER (<@" + creatorId + ">) can click this button!").setEphemeral(true).queue();
                return;
            }

            List<Button> currentButtons = new ArrayList<>(event.getMessage().getButtons());
            List<Button> newButtons = new ArrayList<>();
            boolean buyerConfirmed = false;
            boolean sellerConfirmed = false;

            for (Button b : currentButtons) {
                if (b.getId() != null && b.getId().equals(componentId)) {
                    String label = type.equals("buyerconfirm") ? " Buyer Confirmed" : " Seller Confirmed";
                    newButtons.add(b.asDisabled().withLabel(label).withStyle(net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SUCCESS));
                } else {
                    newButtons.add(b);
                }
            }

            for (Button b : newButtons) {
                if (b.getLabel() != null && b.getLabel().contains("Buyer Confirmed")) buyerConfirmed = true;
                if (b.getLabel() != null && b.getLabel().contains("Seller Confirmed")) sellerConfirmed = true;
            }

            if (buyerConfirmed && sellerConfirmed) {
                event.deferEdit().queue(); 
                
                ThreadChannel thread = event.getChannel().asThreadChannel();
                
                thread.getHistory().retrievePast(50).queue(messages -> {
                    
                    long humanMessageCount = messages.stream().filter(m -> !m.getAuthor().isBot()).count();
                    
                    if (humanMessageCount < 4) {
                        List<Button> resetButtons = new ArrayList<>();
                        for (Button b : newButtons) {
                            if (b.getId() != null && b.getId().startsWith("buyerconfirm_")) {
                                resetButtons.add(Button.primary(b.getId(), "🛍️ Buyer Confirm"));
                            } else if (b.getId() != null && b.getId().startsWith("sellerconfirm_")) {
                                resetButtons.add(Button.primary(b.getId(), "🤝 Seller Confirm"));
                            } else {
                                resetButtons.add(b); 
                            }
                        }
                        
                        event.getHook().editOriginalComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(resetButtons)).queue();
                        event.getHook().sendMessage("<a:0056_huh:1512108926743744663> **Security Lock:** This order cannot be completed yet! The buyer and creator must send at least **4 messages** in this thread to discuss the details before confirming.").setEphemeral(true).queue();
                        return; 
                    }

                    MessageEmbed embed = event.getMessage().getEmbeds().get(0);
                    EmbedBuilder completed = new EmbedBuilder(embed);
                    
                    int totalItems = 1;
                    String desc = embed.getDescription() != null ? embed.getDescription() : "";
                    
                    try { 
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\*\\*CART SIZE: (\\d+)\\*\\*").matcher(desc);
                        if (m.find()) {
                            totalItems = Integer.parseInt(m.group(1));
                        }
                    } catch(Exception e){}

                    db.incrementCreatorOrder(creatorId);

                    completed.setColor(new Color(50, 205, 50)); 
                    completed.setDescription( CinnaSurprise + "**STATUS: COMPLETED**\nSale officially logged for **" + totalItems + "** items!");
                    completed.clearFields(); 

                    List<Button> finalButtons = new ArrayList<>();
                    for (Button b : newButtons) {
                        finalButtons.add(b.asDisabled());
                    }

                    event.getHook().editOriginalEmbeds(completed.build())
                         .setComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(finalButtons))
                         .queue(); 
                    
                    generateAndLogTranscript(thread, "COMPLETED");
                    
                    event.getChannel().sendMessage( CinnaSurprise + " **Transaction Complete!** The sale of **" + totalItems + "** items has been officially logged for <@" + creatorId + ">.")
                         .queue(msg -> {
                             String safeName = thread.getName().replace("⏳", "✅").replace("🔒", "✅").replace("➔", "✔️");
                             thread.getManager().setName(safeName).queue();
                         });
                    
                    sendShopLog(event.getGuild(), "Order Completed", "<@" + creatorId + "> successfully completed a transaction of **" + totalItems + "** items with <@" + buyerId + ">.", new Color(50, 205, 50));

                    EmbedBuilder ratingEmbed = new EmbedBuilder()
                            .setColor(new Color(255, 215, 0))
                            .setTitle("📝 Rate Your Experience!")
                            .setDescription("<@" + buyerId + ">, how was your transaction with <@" + creatorId + ">?");
                    
                    event.getChannel().sendMessageEmbeds(ratingEmbed.build())
                            .addActionRow(
                                Button.secondary("rate_1_" + creatorId + "_" + buyerId, "⭐"),
                                Button.secondary("rate_2_" + creatorId + "_" + buyerId, "⭐⭐"),
                                Button.secondary("rate_3_" + creatorId + "_" + buyerId, "⭐⭐⭐"),
                                Button.secondary("rate_4_" + creatorId + "_" + buyerId, "⭐⭐⭐⭐"),
                                Button.primary("rate_5_" + creatorId + "_" + buyerId, "⭐⭐⭐⭐⭐")
                            )
                            .addActionRow(
                                Button.secondary("ratenone_" + creatorId + "_" + buyerId, "❌ No thanks")
                            ).queue(msg -> {
                                msg.editMessageComponents(java.util.Collections.emptyList())
                                   .queueAfter(15, TimeUnit.MINUTES, success -> {}, error -> {});
                                   
                                thread.getManager().setLocked(true).setArchived(true)
                                      .queueAfter(15, TimeUnit.MINUTES, success -> {}, error -> {});
                            });
                });
            } else {
                event.editComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(newButtons)).queue();
                event.getHook().sendMessage(" Your confirmation has been logged! Waiting for the other party.").setEphemeral(true).queue();
            }
            return;
        }

        if (componentId.startsWith("ordercancel_") || componentId.startsWith("orderdecline_")) {
            String[] parts = componentId.split("_");
            String creatorId = parts[1];
            String buyerId = parts[2];
            
            if (!event.getUser().getId().equals(creatorId) && !event.getUser().getId().equals(buyerId)) {
                event.reply("  Only the buyer or creator involved in this order can cancel it!").setEphemeral(true).queue();
                return;
            }

            EmbedBuilder declined = new EmbedBuilder(event.getMessage().getEmbeds().get(0));
            declined.setColor(Color.RED);
            declined.addField("  STATUS: CANCELLED", "Order was cancelled. No sales logged.", false);
            
            event.editMessageEmbeds(declined.build()).setComponents(java.util.Collections.emptyList()).queue();
            
            ThreadChannel thread = event.getChannel().asThreadChannel();
            generateAndLogTranscript(thread, "CANCELLED/DECLINED");
            
            event.getChannel().sendMessage("Order ticket was marked as cancelled/declined. Locking thread...")
                 .queue(msg -> {
                     String safeName = thread.getName().replace("⏳", "❌").replace("🔒", "❌").replace("➔", "✖️");
                     thread.getManager().setName(safeName).setLocked(true).setArchived(true).queue();
                 });
            
            sendShopLog(event.getGuild(), "Order Cancelled", "A transaction between <@" + creatorId + "> and <@" + buyerId + "> was cancelled by " + event.getUser().getAsMention() + ".", Color.RED);
            return;
        }
        if (componentId.startsWith("rate_") || componentId.startsWith("ratenone_")) {
            String[] parts = componentId.split("_");
            String buyerId = parts[parts.length - 1]; 
            
            if (processedInteractions.contains(event.getMessageId())) return;
            processedInteractions.add(event.getMessageId());
            
            if (!event.getUser().getId().equals(buyerId)) {
                event.reply("<a:67:1525300363849367615>  Only the buyer can interact with this prompt!").setEphemeral(true).queue();
                return;
            }

            EmbedBuilder thanks = new EmbedBuilder().setColor(new Color(255, 215, 0));

            if (componentId.startsWith("ratenone_")) {
                thanks.setDescription("<a:catnod:1527257308755660931>  Understood! Closing this transaction room.");
            } else {
                int stars = Integer.parseInt(parts[1]);
                String creatorId = parts[2];
                db.addCreatorRating(creatorId, stars);
                thanks.setDescription("<a:HatsuneMikuHappy:1525684137237942374>  Thank you! You rated <@" + creatorId + "> **" + stars + " Stars**! Your feedback has been saved.");
                
                String newRatingDisplay = db.getCreatorRatingString(creatorId);
                List<String[]> prompts = db.getCreatorPrompts(creatorId);
                
                for (String[] pair : prompts) {
                    String threadId = pair[0];
                    String msgId = pair[1];
                    
                    ThreadChannel t = event.getJDA().getThreadChannelById(threadId);
                    if (t != null) {
                        t.retrieveMessageById(msgId).queue(oldMsg -> {
                            if (!oldMsg.getEmbeds().isEmpty()) {
                                MessageEmbed oldEmbed = oldMsg.getEmbeds().get(0);
                                String oldDesc = oldEmbed.getDescription();
                                if (oldDesc != null && oldDesc.contains("Creator Rating:")) {
                                    String newDesc = oldDesc.replaceAll("\\*\\*Creator Rating:\\*\\* .*", "**Creator Rating:** " + Matcher.quoteReplacement(newRatingDisplay));
                                    EmbedBuilder updatedEmbed = new EmbedBuilder(oldEmbed).setDescription(newDesc);
                                    oldMsg.editMessageEmbeds(updatedEmbed.build()).queue();
                                }
                            }
                        }, error -> db.removeCreatorPrompt(threadId, msgId));
                    } else {
                        db.removeCreatorPrompt(threadId, msgId); 
                    }
                }
            }

            event.editMessageEmbeds(thanks.build()).setComponents(java.util.Collections.emptyList()).queue(msg -> {
                 event.getChannel().asThreadChannel().getManager().setLocked(true).setArchived(true).queue();
            });
            return;
        }

        if (componentId.equals("bjoin_button")) {
            if (!event.isFromGuild()) {
                event.reply("  This button can only be used inside a server.").setEphemeral(true).queue();
                return;
            }

            net.dv8tion.jda.api.entities.Message message = event.getMessage();
            MessageEmbed oldEmbed = message.getEmbeds().get(0);
            EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);

            String[] partyField = getPartyField(oldEmbed);
            int fieldIndex = getPartyFieldIndex(oldEmbed);

            if (partyField == null || fieldIndex == -1) {
                event.reply("  Party data missing from this announcement.").setEphemeral(true).queue();
                return;
            }

            String partyName = partyField[0];
            String partyValue = partyField[1];
            int current = parsePartyCurrent(partyName);
            String maxStr = parsePartyMax(partyName);
            int max = maxStr.equalsIgnoreCase("Unlimited") ? Integer.MAX_VALUE : Integer.parseInt(maxStr);
            String userMention = event.getUser().getAsMention();

            if (partyValue.contains(userMention)) {
                event.reply("  You are already in the party!").setEphemeral(true).queue();
                return;
            }

            if (current >= max) {
                event.reply("  This event party is full!").setEphemeral(true).queue();
                return;
            }

            partyValue = partyValue.equals("None") ? userMention : partyValue + "\n" + userMention;
            current++;

            newEmbed.getFields().remove(fieldIndex);
            newEmbed.addField("👥 Party [" + current + "/" + maxStr + "]", partyValue, false);
            newEmbed.setColor(Color.YELLOW);

            Button joinBtn = current >= max
                    ? Button.success(componentId, "✋ Join Quest").asDisabled()
                    : Button.success(componentId, "✋ Join Quest");

            message.editMessageEmbeds(newEmbed.build())
                    .setActionRow(joinBtn, Button.danger("bleave_button", "🛑 Leave Quest"))
                    .queue(
                        success -> event.reply(" You have successfully joined the party!").setEphemeral(true).queue(),
                        error -> event.reply("  Failed to join party: " + error.getMessage()).setEphemeral(true).queue()
                    );
            return;
        }

        if (componentId.equals("bleave_button")) {
            if (!event.isFromGuild()) {
                event.reply("  This button can only be used inside a server.").setEphemeral(true).queue();
                return;
            }

            net.dv8tion.jda.api.entities.Message message = event.getMessage();
            MessageEmbed oldEmbed = message.getEmbeds().get(0);
            EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);

            String[] partyField = getPartyField(oldEmbed);
            int fieldIndex = getPartyFieldIndex(oldEmbed);

            if (partyField == null || fieldIndex == -1) {
                event.reply("  Party data missing from this announcement.").setEphemeral(true).queue();
                return;
            }

            String partyName = partyField[0];
            String partyValue = partyField[1];
            int current = parsePartyCurrent(partyName);
            String maxStr = parsePartyMax(partyName);
            String userMention = event.getUser().getAsMention();

            if (!partyValue.contains(userMention)) {
                event.reply("  You are not in the party!").setEphemeral(true).queue();
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
            
            String joinButtonId = message.getButtons().get(0).getId(); 
            if (joinButtonId == null) joinButtonId = "bjoin_button";

            message.editMessageEmbeds(newEmbed.build())
                    .setActionRow(
                            Button.success(joinButtonId, "✋ Join Quest"),
                            Button.danger("bleave_button", "🛑 Leave Quest")
                    )
                    .queue(
                            success -> event.reply(" You have left the party.").setEphemeral(true).queue(),
                            error -> event.reply("  Failed to update the quest party: " + error.getMessage()).setEphemeral(true).queue()
                    );
            return;
        }
        
        if (componentId.equals("alert_party")) {
            if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.reply("⚠️ Only Staff/HR+ can trigger event notifications!").setEphemeral(true).queue();
                return;
            }

            MessageEmbed embed = event.getMessage().getEmbeds().get(0);
            String[] partyField = getPartyField(embed);
            
            if (partyField == null || partyField[1].equals("None") || partyField[1].isEmpty()) {
                event.reply("⚠️ The party is currently empty. There is no one to notify!").setEphemeral(true).queue();
                return;
            }

            List<String> userIds = parseMentionIds(partyField[1]);
            String eventName = embed.getTitle() != null ? embed.getTitle() : "An AMORA Event";
            String threadLink = event.getChannel().asThreadChannel().getAsMention();

            event.reply("🔔 Attempting to send DM reminders to " + userIds.size() + " party members!").setEphemeral(true).queue();

            for (String uid : userIds) {
                event.getJDA().retrieveUserById(uid).queue(user -> {
                    user.openPrivateChannel().flatMap(channel -> {
                        EmbedBuilder dmEmbed = new EmbedBuilder()
                            .setColor(new Color(255, 182, 193))
                            .setTitle("🔔 EVENT REMINDER: " + eventName)
                            .setDescription("Hi " + user.getName() + "!\n\nYou RSVP'd to an event that is **starting very soon**! Please head over to the server and check the event thread here: " + threadLink)
                            .setFooter("AMORA Automated Notifications", null);
                        return channel.sendMessageEmbeds(dmEmbed.build());
                    }).queue(
                        success -> {}, 
                        error -> event.getChannel().sendMessage("⚠️ Could not DM " + user.getAsMention() + " (Their DMs are closed).").queue()
                    );
                }, error -> {});
            }
            
            event.getChannel().sendMessage("📢 " + event.getUser().getAsMention() + " has sent out DM reminders to the party!").queue();
            return;
        }

        if (componentId.startsWith("buy_")) {
            String[] parts = componentId.split("_", 3);
            if (parts.length < 3) {
                event.reply("  Invalid purchase payload.").setEphemeral(true).queue();
                return;
            }

            int price = Integer.parseInt(parts[1]);
            String itemName = decodeItem(parts[2]);
            String clickerId = event.getUser().getId();

            event.deferReply(true).queue();
            synchronized (this) {
                int currentPoints = db.getPoints(clickerId);
                if (getExactItemName(db.getInventory(clickerId), itemName) != null) {
                    event.getHook().sendMessage("  You already own this asset.").queue();
                    return;
                }
                if (currentPoints < price) {
                    event.getHook().sendMessage("  Not enough Points!").queue();
                    return;
                }

                db.updatePoints(clickerId, currentPoints - price);
                db.addInventoryItem(clickerId, itemName);
            }

            EmbedBuilder checkoutEmbed = new EmbedBuilder()
                    .setColor(new Color(255, 182, 193)) 
                    .setTitle("✦ SECURE CHECKOUT COMPLETE ✦")
                    .setDescription(
                            "Your purchase has been processed successfully!\n\n" +
                            "📦 **Asset Acquired:** `" + itemName + "`\n" +
                            "💳 **Points Deducted:** `" + price + " PTS`\n" +
                            "💌 **Delivery Status:** Check your DMs for the secure package.\n\n" +
                            "*Thank you for supporting the AM0RA Marketplace!*"
                    )
                    .setFooter("AM0RA Secure Commerce System", null);

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

            sendShopLog(
                    event.getGuild(),
                    "Shop Direct Purchase",
                    event.getUser().getAsMention() + " bypassed manual ordering and instantly purchased **" + itemName + "** from the market for `"
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
                    .setDescription("  Trade setup cancelled.")
                    .build()).setComponents().queue();
            return;
        }

        if (componentId.startsWith("propose_")) {
            String setupId = componentId.substring("propose_".length());
            DatabaseManager.PendingTradeSetupRecord setup = db.getPendingTradeSetup(setupId);

            if (setup == null || setup.selectedOffer == null || setup.selectedRequest == null) {
                event.reply("  Invalid or expired trade setup.").setEphemeral(true).queue();
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
                            Button.danger("trade_decline_" + tradeId, "  Decline")
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
                event.getChannel().sendMessage("  Trade expired.").queue();
                return;
            }

            if (!event.getUser().getId().equals(trade.targetId) && !event.getUser().getId().equals(trade.senderId)) {
                event.reply("  You are not involved in this trade.").setEphemeral(true).queue();
                return;
            }

            if (action.equals("decline")) {
                db.deleteActiveTrade(tradeId);
                event.editComponents().queue();
                event.getChannel().sendMessage(" :1MikuSad: Trade cancelled.").queue();
                return;
            }

            if (!event.getUser().getId().equals(trade.targetId)) {
                event.reply("  Only the target can accept this trade.").setEphemeral(true).queue();
                return;
            }

            synchronized (this) {
                String senderInv = db.getInventory(trade.senderId);
                String targetInv = db.getInventory(trade.targetId);

                if (getExactItemName(senderInv, trade.offerItem) == null
                        || getExactItemName(targetInv, trade.requestItem) == null) {
                    db.deleteActiveTrade(tradeId);
                    event.editComponents().queue();
                    event.getChannel().sendMessage("  Trade voided. One or more items are missing.").queue();
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