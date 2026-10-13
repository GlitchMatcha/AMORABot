package com.amore;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

public class AnnouncementListener extends ListenerAdapter {

    private static final ScheduledExecutorService EVENT_SCHEDULER = Executors.newScheduledThreadPool(5);
    private static final Map<String, List<ScheduledFuture<?>>> activeTimers = new ConcurrentHashMap<>();

    @Override
    public void onMessageReactionAdd(net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent event) {
        handleScheduleReaction(event.getJDA(), event.getMessageId(), event.getUserId(), event.getMember(), event.getReaction(), true);
    }

    @Override
    public void onMessageReactionRemove(net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent event) {
        handleScheduleReaction(event.getJDA(), event.getMessageId(), event.getUserId(), null, null, false);
    }

    private void handleScheduleReaction(net.dv8tion.jda.api.JDA jda, String messageId, String userId, net.dv8tion.jda.api.entities.Member member, net.dv8tion.jda.api.entities.MessageReaction reaction, boolean isAdding) {
        if (userId == null || userId.equals(jda.getSelfUser().getId())) return;

        DatabaseManager db = DatabaseManager.getInstance();
        String[] syncData = db.getEventSync(messageId);
        if (syncData == null) return;

        String threadId = syncData[0];
        String audience = syncData[2];

        ThreadChannel thread = jda.getThreadChannelById(threadId);
        if (thread == null) return;

        if (isAdding && audience.equals("member")) {
            String memberRoleId = System.getenv("MEMBER_ROLE_ID");
            if (memberRoleId != null && !memberRoleId.isBlank()) {
                if (member == null || member.getRoles().stream().noneMatch(r -> r.getId().equals(memberRoleId))) {
                    if (reaction != null) reaction.removeReaction(jda.getUserById(userId)).queue();
                    return;
                }
            }
        }

        thread.retrieveStartMessage().queue(startMsg -> {
            MessageEmbed embed = startMsg.getEmbeds().get(0);
            String partyField = "";
            int currentSlots = 0;
            int fieldIndex = -1;

            for (int i = 0; i < embed.getFields().size(); i++) {
                MessageEmbed.Field field = embed.getFields().get(i);
                if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                    partyField = field.getValue();
                    fieldIndex = i;
                    break;
                }
            }

            if (fieldIndex == -1) return;
            
            int minSlots = 0;
            int maxSlots = 0;
            if (embed.getFooter() != null && embed.getFooter().getText() != null) {
                Matcher mMin = Pattern.compile("Min:\\s*(\\d+)").matcher(embed.getFooter().getText());
                if (mMin.find()) minSlots = Integer.parseInt(mMin.group(1));
                Matcher mMax = Pattern.compile("Max:\\s*(\\d+)").matcher(embed.getFooter().getText());
                if (mMax.find()) maxSlots = Integer.parseInt(mMax.group(1));
            }
            
            if (partyField != null && !partyField.equals("None")) {
                currentSlots = partyField.split("\n").length;
            }

            String userMention = "<@" + userId + ">";

            if (isAdding) {
                if (partyField.contains(userId)) return;
                if (maxSlots > 0 && currentSlots >= maxSlots) {
                    if (reaction != null) reaction.removeReaction(jda.getUserById(userId)).queue();
                    return;
                }
                partyField = partyField.equals("None") ? userMention : partyField + "\n" + userMention;
                currentSlots++;
            } else {
                if (!partyField.contains(userId)) return;
                String[] users = partyField.split("\n");
                StringBuilder newParty = new StringBuilder();
                for (String u : users) {
                    if (!u.contains(userId) && !u.isBlank()) {
                        if (newParty.length() > 0) newParty.append("\n");
                        newParty.append(u);
                    }
                }
                partyField = newParty.toString();
                if (partyField.isEmpty()) partyField = "None";
                currentSlots--;
            }

            EmbedBuilder newEmbed = new EmbedBuilder(embed);
            newEmbed.getFields().set(fieldIndex, new MessageEmbed.Field(buildPartyFieldTitle(currentSlots, minSlots, maxSlots), partyField, false));
            startMsg.editMessageEmbeds(newEmbed.build()).queue();
        });
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("menu_fused")) return;

        String selectedValue = event.getValues().get(0);
        String[] parts = selectedValue.split(":");
        String type = parts[0];
        String audience = parts[1];
        String urgency = parts[2];

        if (audience.equals("everyone")) {
            boolean isHighStaff = event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR);
            String highStaffIds = System.getenv("HIGH_STAFF_ROLE_IDS");
            
            if (!isHighStaff && highStaffIds != null && !highStaffIds.isBlank()) {
                for (String roleId : highStaffIds.split(",")) {
                    if (event.getMember().getRoles().stream().anyMatch(r -> r.getId().equals(roleId.trim()))) {
                        isHighStaff = true;
                        break;
                    }
                }
            }

            if (!isHighStaff) {
                event.reply("⚠️ **Access Denied:** Only High Staff (Owner, Co-Owner, Head Staff) can host public `Everyone` events! Please select a `Members` event instead.").setEphemeral(true).queue();
                return;
            }
        }

        event.replyModal(buildEventModal("modal_fused:", "Create Hybrid Event", type, audience, urgency, "", "", "", "", "")).queue();
    }
    @Override
    public void onEntitySelectInteraction(net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent event) {
        String componentId = event.getComponentId();

        if (componentId.startsWith("select_add_")) {
            String msgId = componentId.substring("select_add_".length());
            event.getChannel().retrieveMessageById(msgId).queue(msg -> {
                MessageEmbed oldEmbed = msg.getEmbeds().get(0);
                EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);
                
                String partyField = "None";
                int fieldIndex = -1;
                int current = 0;
                String maxStr = "Unlimited";
                
                for (int i = 0; i < oldEmbed.getFields().size(); i++) {
                    MessageEmbed.Field field = oldEmbed.getFields().get(i);
                    if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                        partyField = field.getValue();
                        fieldIndex = i;
                        Matcher m = Pattern.compile("\\[(\\d+)/([\\dUnlimited]+)\\]").matcher(field.getName());
                        if (m.find()) {
                            current = Integer.parseInt(m.group(1));
                            maxStr = m.group(2);
                        }
                        break;
                    }
                }
                
                if (fieldIndex == -1) {
                    event.reply("❌ Party data missing!").setEphemeral(true).queue();
                    return;
                }
                
                int max = maxStr.equalsIgnoreCase("Unlimited") ? Integer.MAX_VALUE : Integer.parseInt(maxStr);
                List<net.dv8tion.jda.api.entities.User> selectedUsers = event.getMentions().getUsers();
                int addedCount = 0;
                
                if (partyField.equals("None")) partyField = "";
                
                for (net.dv8tion.jda.api.entities.User u : selectedUsers) {
                    String mention = u.getAsMention();
                    if (!partyField.contains(mention)) {
                        if (current >= max) break; 
                        partyField += (partyField.isEmpty() ? "" : "\n") + mention;
                        current++;
                        addedCount++;
                    }
                }
                
                if (addedCount == 0) {
                    event.reply("⚠️ No new members were added (Party might be full, or they are already in!).").setEphemeral(true).queue();
                    return;
                }
                
                newEmbed.getFields().remove(fieldIndex);
                newEmbed.addField("👥 Party [" + current + "/" + maxStr + "]", partyField, false);
                if (current >= max) newEmbed.setColor(Color.YELLOW);
                
                msg.editMessageEmbeds(newEmbed.build()).queue();
                event.reply("✅ Successfully added " + addedCount + " member(s) to the party!").setEphemeral(true).queue();
            });
            return;
        }

        if (componentId.startsWith("select_kick_")) {
            String msgId = componentId.substring("select_kick_".length());
            event.getChannel().retrieveMessageById(msgId).queue(msg -> {
                MessageEmbed oldEmbed = msg.getEmbeds().get(0);
                EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);
                
                String partyField = "None";
                int fieldIndex = -1;
                int current = 0;
                String maxStr = "Unlimited";
                
                for (int i = 0; i < oldEmbed.getFields().size(); i++) {
                    MessageEmbed.Field field = oldEmbed.getFields().get(i);
                    if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                        partyField = field.getValue();
                        fieldIndex = i;
                        Matcher m = Pattern.compile("\\[(\\d+)/([\\dUnlimited]+)\\]").matcher(field.getName());
                        if (m.find()) {
                            current = Integer.parseInt(m.group(1));
                            maxStr = m.group(2);
                        }
                        break;
                    }
                }
                
                if (fieldIndex == -1) {
                    event.reply("❌ Party data missing!").setEphemeral(true).queue();
                    return;
                }
                
                List<net.dv8tion.jda.api.entities.User> selectedUsers = event.getMentions().getUsers();
                int removedCount = 0;
                
                for (net.dv8tion.jda.api.entities.User u : selectedUsers) {
                    String mention = u.getAsMention();
                    if (partyField.contains(mention)) {
                        partyField = partyField.replace(mention + "\n", "").replace("\n" + mention, "").replace(mention, "");
                        current--;
                        removedCount++;
                    }
                }
                
                if (partyField.trim().isEmpty()) partyField = "None";
                
                if (removedCount == 0) {
                    event.reply("⚠️ None of the selected users were in the party.").setEphemeral(true).queue();
                    return;
                }
                
                newEmbed.getFields().remove(fieldIndex);
                newEmbed.addField("👥 Party [" + current + "/" + maxStr + "]", partyField, false);
                
                msg.editMessageEmbeds(newEmbed.build()).queue();
                event.reply("✅ Successfully removed " + removedCount + " member(s) from the party!").setEphemeral(true).queue();
            });
            return;
        }

        if (componentId.startsWith("select_exclude_")) {
            String[] parts = componentId.split(":", 3);
            String originalMsgId = parts[0].replace("select_exclude_", "");
            String audience = parts.length > 1 ? parts[1] : "everyone";
            boolean isUrgent = parts.length > 2 && parts[2].equals("urgent");
            
            List<String> excludedIds = event.getMentions().getUsers().stream().map(net.dv8tion.jda.api.entities.User::getId).toList();
            
            event.deferReply(true).queue(hook -> {
            executeEventPayout(hook, event.getUser(), event.getChannel(), originalMsgId, excludedIds, isUrgent, audience);
            event.getMessage().delete().queue(); 
        });
            return;
        }
    }


    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        
        if (event.getModalId().startsWith("modal_complete:")) {
            String[] parts = event.getModalId().split(":");
            String audience = parts[1];
            boolean isUrgent = parts[2].equals("urgent");
            
            String excludeStr = event.getValue("input_exclude") != null ? event.getValue("input_exclude").getAsString() : "";
            List<String> excludedIds = new ArrayList<>();
            Matcher mEx = Pattern.compile("<@!?(\\d+)>").matcher(excludeStr);
            while (mEx.find()) excludedIds.add(mEx.group(1));
            
            Message message = event.getMessage();
            MessageEmbed embed = message.getEmbeds().get(0);
            
            int rewardAmount = 0;
            if (embed.getFooter() != null && embed.getFooter().getText() != null) {
                Matcher mReward = Pattern.compile("Reward embedded:\\s*(\\d+)").matcher(embed.getFooter().getText());
                if (mReward.find()) rewardAmount = Integer.parseInt(mReward.group(1));
            }
            
            String partyData = "None";
            int fieldIndex = -1;
            for (int i = 0; i < embed.getFields().size(); i++) {
                MessageEmbed.Field field = embed.getFields().get(i);
                if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                    partyData = field.getValue();
                    fieldIndex = i;
                    break;
                }
            }
            
            if (partyData == null || partyData.equals("None") || partyData.isEmpty()) {
                event.reply("❌ Cannot complete. The party is empty!").setEphemeral(true).queue();
                return;
            }
            
            List<String> userIds = new ArrayList<>();
            Matcher mUser = Pattern.compile("<@!?(\\d+)>").matcher(partyData);
            while (mUser.find()) userIds.add(mUser.group(1));
            
            StringBuilder payoutLog = new StringBuilder();
            DatabaseManager db = DatabaseManager.getInstance();
            
            for (String uid : userIds) {
                if (excludedIds.contains(uid)) {
                    payoutLog.append("• <@").append(uid).append("> was excluded from the payout.\n");
                    continue;
                }
                
                double multiplier = 1.0;
                boolean isComp = false;
                
                net.dv8tion.jda.api.entities.Member member = event.getGuild().getMemberById(uid);
                if (member != null) {
                    if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Spark"))) multiplier = 1.10;
                    else if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Neon"))) multiplier = 1.25;
                    else if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Pulse"))) multiplier = 1.50;
                    else if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Prism"))) multiplier = 1.75;
                    else if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Supernova"))) multiplier = 2.0;
                    
                    if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Competition Team"))) {
                        multiplier += 0.50;
                        isComp = true;
                    }
                }
                
                int finalReward = (int) Math.round(rewardAmount * multiplier);
                
                int currentPoints = db.getPoints(uid);
                db.updatePoints(uid, currentPoints + finalReward);
                db.incrementBountyStats(uid, isUrgent);
                
                db.ensureConnected();
                try (java.sql.PreparedStatement pstmt = db.ensureConnected().prepareStatement("UPDATE users SET last_task_date = ? WHERE user_id = ?")) {
                    pstmt.setLong(1, System.currentTimeMillis());
                    pstmt.setString(2, uid);
                    pstmt.executeUpdate();
                } catch (Exception ignored) {}

                String bonusText = (multiplier > 1.0) ? " *(x" + multiplier + " Rank Bonus!)*" : "";
                payoutLog.append("• <@").append(uid).append("> received `").append(finalReward).append(" PTS`").append(bonusText).append("\n");
            }
            
            EmbedBuilder completedEmbed = new EmbedBuilder(embed);
            completedEmbed.setColor(new Color(50, 205, 50));
            String title = embed.getTitle();
            if (title != null && !title.startsWith("✅")) {
                completedEmbed.setTitle("✅ [COMPLETED] " + title);
            }
            
            if (fieldIndex != -1) {
                completedEmbed.getFields().remove(fieldIndex);
            }
            completedEmbed.addField("🏆 EVENT CLEARED", "Successfully completed by the party!\n\n" + payoutLog.toString(), false);
            
            event.editMessageEmbeds(completedEmbed.build()).setComponents().queue();
            
            if (event.getChannel().getType().isThread()) {
                ThreadChannel thread = event.getChannel().asThreadChannel();
                
                String targetPingChannelId = audience.equals("member") ? System.getenv("MEMBER_SCHEDULE_CHANNEL_ID") : System.getenv("SCHEDULE_CHANNEL_ID");
                if (targetPingChannelId != null) {
                    TextChannel textChannel = event.getJDA().getTextChannelById(targetPingChannelId);
                    NewsChannel newsChannel = event.getJDA().getNewsChannelById(targetPingChannelId);
                    net.dv8tion.jda.api.entities.channel.middleman.MessageChannel pingChannel = (textChannel != null) ? textChannel : newsChannel;

                    if (pingChannel != null) {
                        String jumpUrl = thread.getJumpUrl();
                        pingChannel.getIterableHistory().takeAsync(100).thenAccept(messages -> {
                            for (Message msg : messages) {
                                if (msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId()) && msg.getContentRaw().contains(jumpUrl)) {
                                    msg.editMessage("✅ **[THIS EVENT IS COMPLETED]**\n~~" + msg.getContentRaw().replace("~~", "") + "~~").queue();
                                    break;
                                }
                            }
                        });
                    }
                }

                thread.sendMessage("🏆 **Event Concluded & Paid:** This event has been completed! All eligible party members have received their Points and profile stats.").queue(
                    success -> thread.getManager().setLocked(true).setArchived(true).queue()
                );
            }
            return;
        }

        boolean isEdit = event.getModalId().startsWith("modal_edit:");
        if (!event.getModalId().startsWith("modal_fused:") && !isEdit) return;

        String prefix = isEdit ? "modal_edit:" : "modal_fused:";
        String payload = event.getModalId().replace(prefix, "");
        String[] parts = payload.split(":");
        String type = parts[0];
        String audience = parts[1];
        String urgency = parts[2];

        String host = event.getValue("input_host").getAsString();
        String slots = event.getValue("input_slots").getAsString();
        String rawTime = event.getValue("input_time").getAsString().trim();
        
        long unixEpoch = parseEventEpoch(rawTime);
        String displayTime;
        long currentEpoch = System.currentTimeMillis() / 1000;

        if (unixEpoch > 0) {
            if (unixEpoch < currentEpoch - 60) {
                event.reply("⚠️ **Invalid Time:** The time you entered (`" + rawTime + "`) is in the past! Please enter a future time for this event.").setEphemeral(true).queue();
                return;
            }
            displayTime = "<t:" + unixEpoch + ":F>\n` ~ ୨୧ · ` <t:" + unixEpoch + ":R>";
        } else {
            // Fallback display if an unparseable text format was entered
            displayTime = "` " + rawTime + " `"; 
        }
        
        int tempReward = 0;
        try { tempReward = Integer.parseInt(event.getValue("input_reward").getAsString().trim()); } catch (Exception ignored) {}
        final int reward = tempReward;

        int[] slotConfig = parseSlotConfig(slots);
        int minSlots = slotConfig[0];
        int maxSlots = slotConfig[1];

        String minHeaderDisplay = (minSlots > 0 ? String.valueOf(minSlots) : "1") + 
                                  (maxSlots > 0 ? " (Max: " + maxSlots + ")" : " or more");

        String extra = event.getValue("input_extra") != null ? event.getValue("input_extra").getAsString() : "";
        String aestheticHeader = buildTemplateHeader(type, host, displayTime, extra, minHeaderDisplay);

        String partyField = "None";
        int currentSlots = 0;
        
        if (isEdit && event.getMessage() != null && !event.getMessage().getEmbeds().isEmpty()) {
            MessageEmbed oldEmbed = event.getMessage().getEmbeds().get(0);
            for (MessageEmbed.Field field : oldEmbed.getFields()) {
                if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                    partyField = field.getValue();
                    Matcher m = Pattern.compile("\\[(\\d+)/").matcher(field.getName());
                    if (m.find()) currentSlots = Integer.parseInt(m.group(1));
                    break;
                }
            }
        }

        String displayTitle = (audience.equals("member") ? (urgency.equals("urgent") ? "🚨 " : "👑 ") : "🌍 ") + type.replace("_", " ").toUpperCase();

        Color embedColor = urgency.equals("urgent") ? new Color(220, 20, 60) : 
                           audience.equals("member") ? new Color(255, 215, 0) : new Color(255, 69, 0);

        String partyFieldTitle = buildPartyFieldTitle(currentSlots, minSlots, maxSlots);

        EmbedBuilder questEmbed = new EmbedBuilder()
                .setColor(embedColor)
                .setTitle(displayTitle)
                .setDescription("💰 **Bounty Reward:** `" + reward + " Points` _(Per Person)_\n" +
                                (audience.equals("member") ? "\n⚠️ **Role Requirement:** Official Members Only!\n" : "") +
                                "\n_Click **Join** below to claim your spot in the party!_")
                .addField(partyFieldTitle, partyField, false)
                .setFooter("AMORA Event Directive • Reward embedded: " + reward + " • Min: " + minSlots + " • Max: " + maxSlots + " • Time: " + rawTime, null);

        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent(aestheticHeader);
        builder.addEmbeds(questEmbed.build());
        
        builder.addActionRow(
                Button.success("qjoin_" + audience, "✋ Join"),
                Button.danger("qleave_button", "⭕ Leave"),
                Button.secondary("alert_party", " Alert the Current Team ")
        );
        builder.addActionRow(
                Button.primary("edit_event:" + type + ":" + audience + ":" + urgency, " Edit Details"),
                Button.success("event_complete:" + audience + ":" + urgency, " Finish & Payout"),
                Button.secondary("event_add", " Add Member"),
                Button.danger("event_kick", " Kick Member")
        );
        builder.addActionRow(
                Button.secondary("backup_request:" + audience, " Call More People! "), 
                Button.secondary("close_event:" + audience, "🔒 Cancel / Close") 
        );

        final long finalUnixEpoch = unixEpoch;

        if (isEdit) {
            event.editMessage(MessageEditData.fromCreateData(builder.build())).queue();
            
            String threadId = event.getChannel().getId();
            scheduleTimers(threadId, finalUnixEpoch, event.getJDA());

            String targetPingChannelId = audience.equals("member") ? System.getenv("MEMBER_SCHEDULE_CHANNEL_ID") : System.getenv("SCHEDULE_CHANNEL_ID");
            
            if (targetPingChannelId != null && event.getChannel().getType().isThread()) {
                TextChannel textChannel = event.getJDA().getTextChannelById(targetPingChannelId);
                NewsChannel newsChannel = event.getJDA().getNewsChannelById(targetPingChannelId);

                net.dv8tion.jda.api.entities.channel.middleman.MessageChannel pingChannel = 
                        (textChannel != null) ? textChannel : newsChannel;

                if (pingChannel != null) {
                    String memberRoleId = System.getenv("MEMBER_ROLE_ID");
                    String pingMention = audience.equals("member") 
                            ? ((memberRoleId != null && !memberRoleId.isBlank()) ? "<@&" + memberRoleId + ">" : "**[Members Only]**") 
                            : "@everyone";

                    String jumpUrl = event.getChannel().asThreadChannel().getJumpUrl();
                    String updatedNotification = aestheticHeader + 
                        "\n\n🔗 **>> [CLICK HERE TO RSVP ON THE QUEST BOARD](" + jumpUrl + ") <<**\n\n" +
                        pingMention + " . 00 . > Amora < . <3.";

                    pingChannel.getIterableHistory().takeAsync(100).thenAccept(messages -> {
                        for (Message msg : messages) {
                            if (msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId()) && msg.getContentRaw().contains(jumpUrl)) {
                                msg.editMessage(updatedNotification).queue();
                                break;
                            }
                        }
                    });
                }
            }
            
            event.getHook().sendMessage("✅ **Event Details Updated:** The Quest Board, Schedules Announcement, and Automated Timers have all been updated.").setEphemeral(true).queue();
            
        } else {
            String targetForumId = audience.equals("member") && urgency.equals("urgent") ? System.getenv("URGENT_BOUNTY_FORUM_ID") : System.getenv("STANDARD_BOUNTY_FORUM_ID");
            
            String targetPingChannelId = audience.equals("member") ? System.getenv("MEMBER_SCHEDULE_CHANNEL_ID") : System.getenv("SCHEDULE_CHANNEL_ID");

            if (targetForumId == null || targetPingChannelId == null) {
                event.reply("⚠️ **Routing Error:** Missing Environment Variables! Ensure `MEMBER_SCHEDULE_CHANNEL_ID` and `SCHEDULE_CHANNEL_ID` are set in the .env file.").setEphemeral(true).queue();
                return;
            }

            ForumChannel targetForum = event.getJDA().getForumChannelById(targetForumId);
            if (targetForum != null) {
                event.deferReply(true).queue();
                
                targetForum.createForumPost(displayTitle, builder.build()).queue(
                    forumPost -> {
                        scheduleTimers(forumPost.getThreadChannel().getId(), finalUnixEpoch, event.getJDA());

                        TextChannel textChannel = event.getJDA().getTextChannelById(targetPingChannelId);
                        NewsChannel newsChannel = event.getJDA().getNewsChannelById(targetPingChannelId);

                        if (textChannel != null || newsChannel != null) {
                            String memberRoleId = System.getenv("MEMBER_ROLE_ID");
                            String pingMention = audience.equals("member") ? ((memberRoleId != null && !memberRoleId.isBlank()) ? "<@&" + memberRoleId + ">" : "**[Members Only]**") : "@everyone";
                            String defaultEmoji = "✋";

                        String notificationMessage = aestheticHeader + 
                            "\n\n🔗 **>> [CLICK HERE TO VIEW QUEST DETAILS](" + forumPost.getThreadChannel().getJumpUrl() + ") <<**\n" +
                            "*(React below or click Instant RSVP to join the party directly!)*\n\n" +
                            pingMention + " . 00 . > Amora < . <3.";

                        net.dv8tion.jda.api.interactions.components.buttons.Button joinBtn = 
                            Button.success("sjoin_" + forumPost.getThreadChannel().getId() + ":" + audience, "✋ Instant RSVP");
                        net.dv8tion.jda.api.interactions.components.buttons.Button leaveBtn = 
                            Button.danger("sleave_" + forumPost.getThreadChannel().getId(), "⭕ Leave");

                        java.util.function.Consumer<Message> onScheduleSent = sentMsg -> {
                            sentMsg.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromFormatted(defaultEmoji)).queue();
                            DatabaseManager.getInstance().saveEventSync(sentMsg.getId(), forumPost.getThreadChannel().getId(), defaultEmoji, audience);
                        };

                        if (textChannel != null) {
                            textChannel.sendMessage(notificationMessage)
                                       .addActionRow(joinBtn, leaveBtn)
                                       .queue(onScheduleSent);
                        } else {
                            newsChannel.sendMessage(notificationMessage)
                                       .addActionRow(joinBtn, leaveBtn)
                                       .queue(onScheduleSent);
                        }
                        event.getHook().sendMessage("✅ Event successfully routed!").queue();
                        } else {
                            event.getHook().sendMessage("✅ Event created in Forum, but ⚠️ **could not find the target schedules channel!** Check permissions and ID.").queue();
                        }
                    },
                    error -> event.getHook().sendMessage("⚠️ Error creating forum post: " + error.getMessage()).queue()
                );
            } else {
                event.reply("Routing Error: Could not find the target forum channel.").setEphemeral(true).queue();
            }
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();
        if (buttonId.startsWith("event_complete:")) {
            if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
                event.reply(" Director clearance required to authorize payouts!").setEphemeral(true).queue();
                return;
            }
            MessageEmbed embed = event.getMessage().getEmbeds().isEmpty() ? null : event.getMessage().getEmbeds().get(0);
            int minRequired = 0;
            
            if (embed != null && embed.getFooter() != null && embed.getFooter().getText() != null) {
                String footerText = embed.getFooter().getText();
                Matcher mTime = Pattern.compile("Time:\\s*(.+)").matcher(footerText);
                if (mTime.find()) {
                    try {
                        String rawTime = mTime.group(1).trim();
                        String smartTime = rawTime.toUpperCase();
                        smartTime = smartTime.replaceAll("(GMT|UTC)\\+(\\d)$", "$1+0$2:00").replaceAll("(GMT|UTC)\\-(\\d)$", "$1-0$2:00").replaceAll("(GMT|UTC)\\+(\\d{2})$", "$1+$2:00").replaceAll("(GMT|UTC)\\-(\\d{2})$", "$1-$2:00");
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
                        long eventEpoch = ZonedDateTime.parse(smartTime, formatter).toEpochSecond();
                        
                        if ((System.currentTimeMillis() / 1000) < eventEpoch) {
                            event.reply("⚠️ **The event hasn't started yet!**\nYou cannot complete and payout an event that is still in the future. To cancel or close this event early without payouts, please click the 🔒 **Cancel / Close** button instead.").setEphemeral(true).queue();
                            return;
                        }
                    } catch (Exception ignored) {}
                }
                // 🚨 Extract Minimum requirement
                Matcher mMin = Pattern.compile("Min:\\s*(\\d+)").matcher(footerText);
                if (mMin.find()) minRequired = Integer.parseInt(mMin.group(1));
            }

            String quotaWarning = "";
            if (minRequired > 0 && embed != null) {
                int currentPartySize = 0;
                for (MessageEmbed.Field f : embed.getFields()) {
                    if (f.getName() != null && f.getName().startsWith("👥 Party")) {
                        if (f.getValue() != null && !f.getValue().equals("None")) {
                            currentPartySize = f.getValue().split("\n").length;
                        }
                        break;
                    }
                }
                if (currentPartySize < minRequired) {
                    quotaWarning = " **NOTICE:** This event had a minimum requirement of **" + minRequired + "** members, but only **" + currentPartySize + "** attended.\n\n";
                }
            }

            String[] parts = buttonId.split(":");
            String audience = parts.length > 1 ? parts[1] : "everyone";
            String urgency = parts.length > 2 ? parts[2] : "standard";
            
            net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu excludeMenu = 
                net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.create("select_exclude_" + event.getMessageId() + ":" + audience + ":" + urgency, net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.SelectTarget.USER)
                .setPlaceholder("Select user(s) to EXCLUDE (Optional)...")
                .setMinValues(1).setMaxValues(25).build();

            event.reply(quotaWarning + " **Ready to Complete & Payout!**\nIf you need to exclude anyone (AFK, troll, left early), select them in the dropdown below. If everyone did a great job, just click **Payout Everyone**!")
                .addActionRow(excludeMenu)
                .addActionRow(Button.success("payout_all_" + event.getMessageId() + ":" + audience + ":" + urgency, "✅ Payout Everyone!"))
                .setEphemeral(true).queue();
            return;
        }
        
        if (event.getComponentId().equals("hub_comp")) {
            boolean isEligible = event.getMember().getRoles().stream()
                .anyMatch(r -> java.util.Arrays.asList("Neon", "Pulse", "Prism", "Supernova").contains(r.getName()));
            
            if (!isEligible) {
                event.reply(" **Access Denied:** You must be at least **Neon** rank to apply for the Competition Team! Keep working hard and ranking up!").setEphemeral(true).queue();
                return;
            }
            
            net.dv8tion.jda.api.interactions.components.text.TextInput whyComp = net.dv8tion.jda.api.interactions.components.text.TextInput.create("comp_why", "Why do you want to join Comp?", net.dv8tion.jda.api.interactions.components.text.TextInputStyle.PARAGRAPH).setRequired(true).build();
            net.dv8tion.jda.api.interactions.components.text.TextInput compExp = net.dv8tion.jda.api.interactions.components.text.TextInput.create("comp_exp", "Past Comp/Dance Experience?", net.dv8tion.jda.api.interactions.components.text.TextInputStyle.PARAGRAPH).setRequired(true).build();
            
            net.dv8tion.jda.api.interactions.modals.Modal modal = net.dv8tion.jda.api.interactions.modals.Modal.create("modal_comp", "Competition Team Audition")
                .addActionRow(whyComp).addActionRow(compExp).build();
                
            event.replyModal(modal).queue();
            return;
        }
        if (buttonId.equals("event_add")) {
            if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
                event.reply("Director clearance required to manually add members!").setEphemeral(true).queue();
                return;
            }
            net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu menu = 
                net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.create("select_add_" + event.getMessageId(), net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.SelectTarget.USER)
                .setPlaceholder("Select user(s) to manually add...")
                .setMinValues(1).setMaxValues(10).build();
                
            event.reply("Please select the user(s) you want to add to the party:")
                .addActionRow(menu).setEphemeral(true).queue();
            return;
        }

        if (buttonId.equals("event_kick")) {
            if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
                event.reply("Director clearance required to kick members!").setEphemeral(true).queue();
                return;
            }
            net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu menu = 
                net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.create("select_kick_" + event.getMessageId(), net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.SelectTarget.USER)
                .setPlaceholder("Select user(s) to kick from the party...")
                .setMinValues(1).setMaxValues(10).build();
                
            event.reply("Please select the user(s) you want to kick:")
                .addActionRow(menu).setEphemeral(true).queue();
            return;
        }
        
        if (buttonId.startsWith("payout_all_")) {
            String[] parts = buttonId.split(":", 3);
            String originalMsgId = parts[0].replace("payout_all_", "");
            String audience = parts.length > 1 ? parts[1] : "everyone";
            boolean isUrgent = parts.length > 2 && parts[2].equals("urgent");
            
            event.deferReply(true).queue(hook -> {
            executeEventPayout(hook, event.getUser(), event.getChannel(), originalMsgId, new ArrayList<>(), isUrgent, audience);
            event.getMessage().delete().queue(); 
        });
            return;
        }
        if (buttonId.startsWith("backup_request:")) {
            if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.MESSAGE_MANAGE)) {
                event.reply(" **Access Denied:** Only Staff can request backup!").setEphemeral(true).queue();
                return;
            }

            String[] parts = buttonId.split(":");
            String audience = parts.length > 1 ? parts[1] : "everyone";
            String msgId = event.getMessageId();

            event.reply("📢 **Where would you like to broadcast the backup call?**\n*Note: The broadcast will auto-delete after 15 minutes to keep chat clean.*")
                .addActionRow(
                    Button.primary("route_backup:lounge:" + msgId + ":" + audience, " Lounge Channel"),
                    Button.primary("route_backup:member:" + msgId + ":" + audience, " Member Chat Channel"),
                    Button.success("route_backup:both:" + msgId + ":" + audience, " Both Channels")
                ).setEphemeral(true).queue();
            return;
        }
        if (buttonId.startsWith("route_backup:")) {
            String[] parts = buttonId.split(":");
            String target = parts[1]; 
            String origMsgId = parts[2];
            String audience = parts[3];

            event.getChannel().retrieveMessageById(origMsgId).queue(origMsg -> {
                MessageEmbed embed = origMsg.getEmbeds().isEmpty() ? null : origMsg.getEmbeds().get(0);
                if (embed == null) {
                    event.reply(" Error: Original event data not found.").setEphemeral(true).queue();
                    return;
                }

                String eventTitle = embed.getTitle() != null ? embed.getTitle().replace("🎯 DIRECTIVE: ", "") : "An AMORA Event";
                
                String loungeId = System.getenv("LOUNGE_CHANNEL_ID"); 
                String memberChatId = System.getenv("MEMBER_CHAT_CHANNEL_ID"); 
                String memberRoleId = System.getenv("MEMBER_ROLE_ID");

                String pingMention = audience.equals("member") 
                        ? ((memberRoleId != null && !memberRoleId.isBlank()) ? "<@&" + memberRoleId + ">" : "**[Members Only]**") 
                        : "@everyone";

                EmbedBuilder broadcast = new EmbedBuilder()
                    .setColor(new Color(255, 182, 193))
                    .setDescription(" **" + eventTitle + "** is starting soon!\n\n" +
                                    "If you haven't responded, you can still come and check it out in the event-training board!.\n\n" +
                                    "🔗 **[Click here to jump to the event ticket!](" + origMsg.getJumpUrl() + ")**")
                    .setFooter("This reminder will self-destruct in 15 minutes to keep chat clean ", null);

                boolean sentLounge = false;
                boolean sentMember = false;

                if ((target.equals("lounge") || target.equals("both")) && loungeId != null && !loungeId.isBlank()) {
                    TextChannel lounge = event.getJDA().getTextChannelById(loungeId);
                    if (lounge != null) {
                        lounge.sendMessage(pingMention).setEmbeds(broadcast.build()).queue(msg -> {
                            msg.delete().queueAfter(15, TimeUnit.MINUTES, success -> {}, error -> {});
                        });
                        sentLounge = true;
                    }
                }

                if ((target.equals("member") || target.equals("both")) && memberChatId != null && !memberChatId.isBlank()) {
                    TextChannel memberChat = event.getJDA().getTextChannelById(memberChatId);
                    if (memberChat != null) {
                        memberChat.sendMessage(pingMention).setEmbeds(broadcast.build()).queue(msg -> {
                            msg.delete().queueAfter(15, TimeUnit.MINUTES, success -> {}, error -> {});
                        });
                        sentMember = true;
                    }
                }

               
                String result = "Event/Training calling successfully sent to: **" + 
                    (sentLounge && sentMember ? "Lounge & Member Chat" : 
                    (sentLounge ? "Lounge" : 
                    (sentMember ? "Member Chat" : "None (Channels not configured!)"))) + "**";

                event.editMessage(result).setComponents().queue();

            }, error -> {
                event.reply(" Error retrieving original event message.").setEphemeral(true).queue();
            });
            return;
        }

        if (buttonId.startsWith("close_event:")) {
            if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.MESSAGE_MANAGE)) {
                event.reply("⚠️ **Access Denied:** Only Staff can close events!").setEphemeral(true).queue();
                return;
            }

            String audience = buttonId.split(":")[1];
            Message message = event.getMessage();
            MessageEmbed embed = message.getEmbeds().isEmpty() ? null : message.getEmbeds().get(0);
            
            if (embed == null) return;

            EmbedBuilder closedEmbed = new EmbedBuilder(embed);
            closedEmbed.setColor(Color.DARK_GRAY);
            String title = embed.getTitle();
            if (title != null && !title.startsWith("🔒")) {
                closedEmbed.setTitle("🔒 [CLOSED/CANCELLED] " + title);
            }
            
            event.editMessageEmbeds(closedEmbed.build()).setComponents(java.util.Collections.emptyList()).queue();

            if (event.getChannel().getType().isThread()) {
                ThreadChannel thread = event.getChannel().asThreadChannel();
                
                String targetPingChannelId = audience.equals("member") ? System.getenv("MEMBER_SCHEDULE_CHANNEL_ID") : System.getenv("SCHEDULE_CHANNEL_ID");
                if (targetPingChannelId != null) {
                    TextChannel textChannel = event.getJDA().getTextChannelById(targetPingChannelId);
                    NewsChannel newsChannel = event.getJDA().getNewsChannelById(targetPingChannelId);
                    net.dv8tion.jda.api.entities.channel.middleman.MessageChannel pingChannel = (textChannel != null) ? textChannel : newsChannel;

                    if (pingChannel != null) {
                        String jumpUrl = thread.getJumpUrl();
                        pingChannel.getIterableHistory().takeAsync(100).thenAccept(messages -> {
                            for (Message msg : messages) {
                                if (msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId()) && msg.getContentRaw().contains(jumpUrl)) {
                                    msg.editMessage("🔒 **[THIS EVENT WAS CANCELLED/CLOSED]**\n~~" + msg.getContentRaw().replace("~~", "") + "~~").queue();
                                    break;
                                }
                            }
                        });
                    }
                }

                thread.sendMessage("🔒 **Event Concluded/Cancelled:** This quest thread has been officially closed by Staff without payouts.").queue(
                    success -> thread.getManager().setLocked(true).setArchived(true).queue()
                );
            }
            return;
        }

        if (buttonId.startsWith("edit_event:")) {
            if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.MESSAGE_MANAGE)) {
                event.reply("⚠️ **Access Denied:** Only Staff can edit events!").setEphemeral(true).queue();
                return;
            }
            
            String[] parts = buttonId.split(":");
            String type = parts[1];
            String audience = parts[2];
            String urgency = parts[3];

            Message message = event.getMessage();
            String content = message.getContentRaw();
            MessageEmbed embed = message.getEmbeds().isEmpty() ? null : message.getEmbeds().get(0);

            String host = "";
            Matcher mHost = Pattern.compile("(?i)(?:𝐓𝐫𝐚𝐢𝐧𝐞𝐫|𝐇𝐨𝐬𝐭)\\s*:`?\\s*\\n+\\s*`\\s*~\\s*୨୧\\s*·\\s*(.*?)\\s*`").matcher(content);
            if (mHost.find()) host = mHost.group(1).trim();

            String extra = "";
            Matcher mExtra = Pattern.compile("(?i)(?:𝐒𝐞𝐫𝐯𝐞𝐫(?: / 𝐓𝐲𝐩𝐞)?|𝐓𝐡𝐞𝐦𝐞|𝐆𝐚𝐦𝐞𝐬)\\s*:`?\\s*\\n+\\s*`\\s*~\\s*୨୧\\s*·\\s*(.*?)\\s*`").matcher(content);
            if (mExtra.find()) extra = mExtra.group(1).trim();

            String rewardStr = "0";
            String timeStr = "";
            String slots = "";

            if (embed != null && embed.getFooter() != null && embed.getFooter().getText() != null) {
                String footerText = embed.getFooter().getText();
                Matcher mReward = Pattern.compile("Reward embedded:\\s*(\\d+)").matcher(footerText);
                if (mReward.find()) rewardStr = mReward.group(1);
                
                // 🚨 Read saved Min/Max configuration
                Matcher mMin = Pattern.compile("Min:\\s*(\\d+)").matcher(footerText);
                Matcher mMax = Pattern.compile("Max:\\s*(\\d+)").matcher(footerText);
                int savedMin = mMin.find() ? Integer.parseInt(mMin.group(1)) : 0;
                int savedMax = mMax.find() ? Integer.parseInt(mMax.group(1)) : 0;
                if (savedMin > 0 || savedMax > 0) {
                    slots = savedMin + " / " + (savedMax <= 0 ? "Unlimited" : savedMax);
                }

                Matcher mTime = Pattern.compile("Time:\\s*(.+)").matcher(footerText);
                if (mTime.find()) timeStr = mTime.group(1).trim();
            }

            if (timeStr.isEmpty()) {
                Matcher mEpoch = Pattern.compile("<t:(\\d+):F>").matcher(content);
                if (mEpoch.find()) {
                    long epoch = Long.parseLong(mEpoch.group(1));
                    ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.of("UTC"));
                    timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " UTC";
                } else {
                    Matcher mTimeRaw = Pattern.compile("(?i)𝐓𝐢𝐦𝐞\\s*:`?\\s*\\n+\\s*`\\s*~\\s*୨୧\\s*·\\s*`?\\s*(.*?)\\s*`").matcher(content);
                    if (mTimeRaw.find()) timeStr = mTimeRaw.group(1).trim();
                }
            }

            event.replyModal(buildEventModal("modal_edit:", "Edit Event", type, audience, urgency, host, timeStr, extra, slots, rewardStr)).queue();
            return;
        }
        // --- ⚡ INSTANT RSVP BUTTONS DIRECTLY FROM SCHEDULES CHANNEL ---
        if (buttonId.startsWith("sjoin_") || buttonId.startsWith("sleave_")) {
            boolean isJoining = buttonId.startsWith("sjoin_");
            String payload = buttonId.replace(isJoining ? "sjoin_" : "sleave_", "");
            String[] parts = payload.split(":");
            String threadId = parts[0];
            String audience = parts.length > 1 ? parts[1] : "everyone";

            if (isJoining && audience.equals("member")) {
                String memberRoleId = System.getenv("MEMBER_ROLE_ID");
                if (memberRoleId != null && !memberRoleId.isBlank()) {
                    if (event.getMember().getRoles().stream().noneMatch(r -> r.getId().equals(memberRoleId))) {
                        event.reply(" **Access Denied:** This event is strictly for Official Members!").setEphemeral(true).queue();
                        return;
                    }
                }
            }

            ThreadChannel thread = event.getJDA().getThreadChannelById(threadId);
            if (thread == null) {
                event.reply(" Could not locate the Quest Board. It may have been closed.").setEphemeral(true).queue();
                return;
            }

            event.deferReply(true).queue(hook -> {
                thread.retrieveStartMessage().queue(startMsg -> {
                    MessageEmbed embed = startMsg.getEmbeds().get(0);
                    String partyField = "";
                    int currentSlots = 0;
                    int fieldIndex = -1;

                    for (int i = 0; i < embed.getFields().size(); i++) {
                        MessageEmbed.Field field = embed.getFields().get(i);
                        if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                            partyField = field.getValue();
                            fieldIndex = i;
                            break;
                        }
                    }

                    if (fieldIndex == -1) {
                        hook.sendMessage(" Error: Team data could not be parsed.").queue();
                        return;
                    }

                    int minSlots = 0;
                    int maxSlots = 0;
                    if (embed.getFooter() != null && embed.getFooter().getText() != null) {
                        Matcher mMin = Pattern.compile("Min:\\s*(\\d+)").matcher(embed.getFooter().getText());
                        if (mMin.find()) minSlots = Integer.parseInt(mMin.group(1));
                        Matcher mMax = Pattern.compile("Max:\\s*(\\d+)").matcher(embed.getFooter().getText());
                        if (mMax.find()) maxSlots = Integer.parseInt(mMax.group(1));
                    }
                    
                    if (partyField != null && !partyField.equals("None")) {
                        currentSlots = partyField.split("\n").length;
                    }

                    String userId = event.getUser().getId();
                    String userMention = "<@" + userId + ">";

                    if (isJoining) {
                        if (partyField.contains(userId)) {
                            hook.sendMessage(" You are already in the team!").queue();
                            return;
                        }
                        if (maxSlots > 0 && currentSlots >= maxSlots) {
                            hook.sendMessage(" **Team Full:** There are no open slots remaining!").queue();
                            return;
                        }
                        partyField = partyField.equals("None") ? userMention : partyField + "\n" + userMention;
                        currentSlots++;
                    } else {
                        if (!partyField.contains(userId)) {
                            hook.sendMessage(" You are not in the team!").queue();
                            return;
                        }
                        String[] users = partyField.split("\n");
                        StringBuilder newParty = new StringBuilder();
                        for (String u : users) {
                            if (!u.contains(userId) && !u.isBlank()) {
                                if (newParty.length() > 0) newParty.append("\n");
                                newParty.append(u);
                            }
                        }
                        partyField = newParty.toString();
                        if (partyField.isEmpty()) partyField = "None";
                        currentSlots--;
                    }

                    EmbedBuilder newEmbed = new EmbedBuilder(embed);
                    newEmbed.getFields().set(fieldIndex, new MessageEmbed.Field(buildPartyFieldTitle(currentSlots, minSlots, maxSlots), partyField, false));
                    startMsg.editMessageEmbeds(newEmbed.build()).queue();

                    hook.sendMessage(isJoining ? " **Matcha confirmed!** You have been added to the team." : " **Matcha canceled!** You have left the team.").queue();
                }, err -> hook.sendMessage(" Error syncing with the Quest Board.").queue());
            });
            return;
        }
        if (buttonId.startsWith("qjoin_") || buttonId.equals("qleave_button") || buttonId.equals("alert_party")) {
            
            MessageEmbed embed = event.getMessage().getEmbeds().get(0);
            String partyField = "";
            int currentSlots = 0;
            int fieldIndex = -1;

            for (int i = 0; i < embed.getFields().size(); i++) {
                MessageEmbed.Field field = embed.getFields().get(i);
                if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                    partyField = field.getValue();
                    fieldIndex = i;
                    break;
                }
            }

            if (fieldIndex == -1) {
                event.reply(" Error: Could not read party data.").setEphemeral(true).queue();
                return;
            }

            int minSlots = 0;
            int maxSlots = 0;
            if (embed.getFooter() != null && embed.getFooter().getText() != null) {
                Matcher mMin = Pattern.compile("Min:\\s*(\\d+)").matcher(embed.getFooter().getText());
                if (mMin.find()) minSlots = Integer.parseInt(mMin.group(1));
                Matcher mMax = Pattern.compile("Max:\\s*(\\d+)").matcher(embed.getFooter().getText());
                if (mMax.find()) maxSlots = Integer.parseInt(mMax.group(1));
            }
            
            if (partyField != null && !partyField.equals("None")) {
                currentSlots = partyField.split("\n").length;
            }

            String userId = event.getUser().getId();
            String userMention = "<@" + userId + ">";

            if (buttonId.equals("alert_party")) {
                if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.MESSAGE_MANAGE)) {
                    event.reply(" **Access Denied:** Only Staff can manually alert the team!").setEphemeral(true).queue();
                    return;
                }
                if (partyField.equals("None") || partyField.isEmpty()) {
                    event.reply(" The team is currently empty!").setEphemeral(true).queue();
                    return;
                }
                
                StringBuilder pings = new StringBuilder(" **MANUAL STAFF ALERT:**\n");
                Matcher m = Pattern.compile("<@!?(\\d+)>").matcher(partyField);
                while (m.find()) pings.append("<@").append(m.group(1)).append("> ");
                
                event.getMessage().getChannel().sendMessage(pings.toString()).queue();
                event.reply(" The team has been successfully alerted!").setEphemeral(true).queue();
                return;
            }

            boolean isJoining = buttonId.startsWith("qjoin_");

            if (isJoining && buttonId.equals("qjoin_member")) {
                String memberRoleId = System.getenv("MEMBER_ROLE_ID");
                if (memberRoleId != null && !memberRoleId.isBlank()) {
                    if (event.getMember().getRoles().stream().noneMatch(r -> r.getId().equals(memberRoleId))) {
                        event.reply(" **Access Denied:** This event is strictly for Official Members!").setEphemeral(true).queue();
                        return;
                    }
                }
            }

            if (isJoining) {
                if (partyField.contains(userId)) {
                    event.reply(" You are already in the team!").setEphemeral(true).queue();
                    return;
                }
                if (maxSlots > 0 && currentSlots >= maxSlots) {
                    event.reply(" **Team Full:** There are no slots remaining for this event!").setEphemeral(true).queue();
                    return;
                }
                partyField = partyField.equals("None") ? userMention : partyField + "\n" + userMention;
                currentSlots++;
            } else {
                if (!partyField.contains(userId)) {
                    event.reply(" You aren't in the team!").setEphemeral(true).queue();
                    return;
                }
                String[] users = partyField.split("\n");
                StringBuilder newParty = new StringBuilder();
                for (String u : users) {
                    if (!u.contains(userId) && !u.isBlank()) {
                        if (newParty.length() > 0) newParty.append("\n");
                        newParty.append(u);
                    }
                }
                partyField = newParty.toString();
                if (partyField.isEmpty()) partyField = "None";
                currentSlots--;
            }

            EmbedBuilder newEmbed = new EmbedBuilder(embed);
            newEmbed.getFields().set(fieldIndex, new MessageEmbed.Field(buildPartyFieldTitle(currentSlots, minSlots, maxSlots), partyField, false));
            
            event.editMessageEmbeds(newEmbed.build())
                 .setComponents(event.getMessage().getComponents()) 
                 .queue();
            }
        }

    private Modal buildEventModal(String idPrefix, String title, String type, String audience, String urgency, 
                                  String defaultHost, String defaultTime, String defaultExtra, String defaultSlots, String defaultReward) {
        
        TextInput.Builder hostBuilder = TextInput.create("input_host", "Host / Trainer", TextInputStyle.SHORT)
                .setPlaceholder("e.g. @Matcha or Username").setRequired(true);
        if (type.equals("training") || type.equals("training_comp")) hostBuilder.setLabel("Trainer");
        if (!defaultHost.isBlank()) hostBuilder.setValue(defaultHost);

        TextInput.Builder timeBuilder = TextInput.create("input_time", "Time (Date Time Timezone)", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 2026-10-15 20:00 GMT+7 or 8:00 PM GMT+5:30").setRequired(true);
        if (!defaultTime.isBlank()) timeBuilder.setValue(defaultTime);

        TextInput.Builder slotsBuilder = TextInput.create("input_slots", "Party Capacity (Min / Max)", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 2 / 10 (Min 2, Max 10) or 2 / Unlimited").setRequired(true);
        if (!defaultSlots.isBlank()) slotsBuilder.setValue(defaultSlots);

        TextInput.Builder rewardBuilder = TextInput.create("input_reward", "Reward (Points per person)", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 50").setRequired(true);
        if (!defaultReward.isBlank()) rewardBuilder.setValue(defaultReward);

        Modal.Builder modal = Modal.create(idPrefix + type + ":" + audience + ":" + urgency, title);
        modal.addActionRow(hostBuilder.build());
        modal.addActionRow(timeBuilder.build());

        if (type.equals("game")) {
            TextInput.Builder extraBuilder = TextInput.create("input_extra", "Games", TextInputStyle.SHORT).setRequired(true);
            if (!defaultExtra.isBlank()) extraBuilder.setValue(defaultExtra);
            modal.addActionRow(extraBuilder.build());
        } else if (type.equals("fashion") || type.equals("training_comp")) {
            TextInput.Builder extraBuilder = TextInput.create("input_extra", "Theme", TextInputStyle.SHORT).setRequired(true);
            if (!defaultExtra.isBlank()) extraBuilder.setValue(defaultExtra);
            modal.addActionRow(extraBuilder.build());
        } else if (type.equals("training")) {
            TextInput.Builder extraBuilder = TextInput.create("input_extra", "Server & Type (e.g. VIP - Lyrics)", TextInputStyle.SHORT).setRequired(true);
            if (!defaultExtra.isBlank()) extraBuilder.setValue(defaultExtra);
            modal.addActionRow(extraBuilder.build());
        } else if (!type.equals("movie")) {
            TextInput.Builder extraBuilder = TextInput.create("input_extra", "Server", TextInputStyle.SHORT).setRequired(true);
            if (!defaultExtra.isBlank()) extraBuilder.setValue(defaultExtra);
            modal.addActionRow(extraBuilder.build());
        }

        modal.addActionRow(slotsBuilder.build());
        modal.addActionRow(rewardBuilder.build());
        return modal.build();
    }

    private void scheduleTimers(String threadId, long finalUnixEpoch, net.dv8tion.jda.api.JDA jda) {
        List<ScheduledFuture<?>> oldTimers = activeTimers.remove(threadId);
        if (oldTimers != null) oldTimers.forEach(t -> t.cancel(false));

        if (finalUnixEpoch <= 0) return;

        long currentTime = System.currentTimeMillis() / 1000;
        long secondsUntil30Mins = finalUnixEpoch - currentTime - (30 * 60); 
        long secondsUntilStart = finalUnixEpoch - currentTime; 
        
        List<ScheduledFuture<?>> newTimers = new ArrayList<>();

        if (secondsUntil30Mins > 0) {
            newTimers.add(EVENT_SCHEDULER.schedule(() -> {
                ThreadChannel threadChannel = jda.getThreadChannelById(threadId);
                if (threadChannel == null) return;
                
                String dmMsg = "# ୧ ╰ 𝐀𝐌𝐎𝐑𝐀 𝐄𝐕𝐄𝐍𝐓 𝐑𝐄𝐌𝐈𝐍𝐃𝐄𝐑 . .ᐟ\n" +
                               " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n" +
                               "`~ ୨୧ · ` 𝐇𝐢 **%s**!\n" +
                               "`~ ୨୧ · ` 𝐓𝐡𝐞 𝐞𝐯𝐞𝐧𝐭 𝐲𝐨𝐮 𝐑𝐒𝐕𝐏'𝐝 𝐭𝐨 𝐢𝐬 𝐬𝐭𝐚𝐫𝐭𝐢𝐧𝐠 𝐢𝐧 **𝟑𝟎 𝐌𝐢𝐧𝐮𝐭𝐞𝐬**!\n" +
                               "`~ ୨୧ · ` 𝐏𝐥𝐞𝐚𝐬𝐞 𝐬𝐭𝐚𝐫𝐭 𝐠𝐞𝐭𝐭𝐢𝐧𝐠 𝐫𝐞𝐚𝐝𝐲.. ⑅<:SCfeltcutemightdeletelateridk:1526912666835357736>\n\n" +
                               "🔗 **>> [CLICK HERE TO JUMP TO THE EVENT](" + threadChannel.getJumpUrl() + ") <<**";
                String threadMsg = "🔔 **AUTOMATED REMINDER:** The event is starting in 30 minutes! Warning DMs have been dispatched to the party.";
                sendPartyReminders(threadChannel, jda, dmMsg, threadMsg);
            }, secondsUntil30Mins, TimeUnit.SECONDS));
        }

        if (secondsUntilStart > 0) {
            newTimers.add(EVENT_SCHEDULER.schedule(() -> {
                ThreadChannel threadChannel = jda.getThreadChannelById(threadId);
                if (threadChannel == null) return;
                
                String dmMsg = "# ୧ ╰ 𝐀𝐌𝐎𝐑𝐀 𝐄𝐕𝐄𝐍𝐓 𝐒𝐓𝐀𝐑𝐓𝐈𝐍𝐆 . .ᐟ\n" +
                               " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n" +
                               "`~ ୨୧ · ` 𝐇𝐢 **%s**!\n" +
                               "`~ ୨୧ · ` 𝐓𝐡𝐞 𝐞𝐯𝐞𝐧𝐭 𝐲𝐨𝐮 𝐑𝐒𝐕𝐏'𝐝 𝐭𝐨 𝐢𝐬 𝐬𝐭𝐚𝐫𝐭𝐢𝐧𝐠 **𝐑𝐈𝐆𝐇𝐓 𝐍𝐎𝐖**!\n" +
                               "`~ ୨୧ · ` 𝐏𝐥𝐞𝐚𝐬𝐞 𝐡𝐞𝐚𝐝 𝐭𝐨 𝐭𝐡𝐞 𝐬𝐞𝐫𝐯𝐞𝐫 𝐢𝐦𝐦𝐞𝐝𝐢𝐚𝐭𝐞𝐥𝐲.. ⑅<a:animehype:1514915354894405702>\n\n" +
                               "🔗 **>> [CLICK HERE TO JUMP TO THE EVENT](" + threadChannel.getJumpUrl() + ") <<**";
                String threadMsg = "🚨 **EVENT STARTING NOW:** The event has officially begun! Final DMs have been dispatched to the party.";
                sendPartyReminders(threadChannel, jda, dmMsg, threadMsg);
            }, secondsUntilStart, TimeUnit.SECONDS));
        }

        if (!newTimers.isEmpty()) {
            activeTimers.put(threadId, newTimers);
        }
    }

    private void sendPartyReminders(ThreadChannel threadChannel, net.dv8tion.jda.api.JDA jda, String dmTemplate, String threadAnnouncement) {
        threadChannel.retrieveStartMessage().queue(startMsg -> {
            if (startMsg.getEmbeds().isEmpty()) return;
            MessageEmbed embed = startMsg.getEmbeds().get(0);
            
            String partyData = "";
            for (MessageEmbed.Field field : embed.getFields()) {
                if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                    partyData = field.getValue();
                    break;
                }
            }
            
            if (partyData == null || partyData.equals("None") || partyData.isEmpty()) return;

            List<String> userIds = new ArrayList<>();
            Matcher m = Pattern.compile("<@!?(\\d+)>").matcher(partyData);
            while (m.find()) userIds.add(m.group(1));

            for (String uid : userIds) {
                jda.retrieveUserById(uid).queue(user -> {
                    user.openPrivateChannel().queue(pc -> {
                        pc.sendMessage(dmTemplate.replace("%s", user.getName())).queue(s->{}, e->{});
                    });
                });
            }
            
            threadChannel.sendMessage(threadAnnouncement).queue();
        });
    }

    private String buildTemplateHeader(String type, String host, String time, String extra, String members) {
        switch (type) {
            case "training":
                return "# ୧ ╰ 𝐀𝐌𝐎𝐑𝐀 𝐓𝐑𝐀𝐈𝐍𝐈𝐍𝐆 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐫𝐚𝐢𝐧𝐞𝐫 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ · ` " + time + "\n\n\n" +
                       "`~ ୨୧ ·  𝐒𝐞𝐫𝐯𝐞𝐫 / 𝐓𝐲𝐩𝐞 :` \n\n" +
                       "` ~ ୨୧ ·  " + extra + " `\n\n\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝 :` \n\n" +
                       "` ~ ୨୧ ·  " + members + " `\n\n\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " 𝐏𝐥𝐞𝐚𝐬𝐞 𝐜𝐥𝐢𝐜𝐤 𝐭𝐡𝐞 𝐐𝐮𝐞𝐬𝐭 𝐁𝐨𝐚𝐫𝐝 𝐥𝐢𝐧𝐤 𝐛𝐞𝐥𝐨𝐰 𝐭𝐨 𝐑𝐒𝐕𝐏 𝐢𝐟 𝐲𝐨𝐮 𝐚𝐫𝐞 𝐬𝐮𝐫𝐞 𝐲𝐨𝐮 𝐜𝐚𝐧 𝐚𝐭𝐭𝐞𝐧𝐝.. ⑅<:Hai:1526912714889494589>\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _";

            case "movie":
                return "# ୧ ╰ 𝐌𝐎𝐕𝐈𝐄 𝐍𝐈𝐆𝐇𝐓 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ · ` " + time + "\n\n\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝 :` \n\n" +
                       "` ~ ୨୧ ·  " + members + " `\n\n\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " 𝐏𝐥𝐞𝐚𝐬𝐞 𝐜𝐥𝐢𝐜𝐤 𝐭𝐡𝐞 𝐐𝐮𝐞𝐬𝐭 𝐁𝐨𝐚𝐫𝐝 𝐥𝐢𝐧𝐤 𝐛𝐞𝐥𝐨𝐰 𝐭𝐨 𝐑𝐒𝐕𝐏 𝐢𝐟 𝐲𝐨𝐮 𝐚𝐫𝐞 𝐬𝐮𝐫𝐞 𝐲𝐨𝐮 𝐜𝐚𝐧 𝐚𝐭𝐭𝐞𝐧𝐝.. ⑅<:SCfeltcutemightdeletelateridk:1526912666835357736>\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _";

            case "game":
                return "# ୧ ╰ 𝐆𝐀𝐌𝐄 𝐍𝐈𝐆𝐇𝐓 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ · ` " + time + "\n\n\n" +
                       "`~ ୨୧ ·  𝐆𝐚𝐦𝐞𝐬 :` \n\n" +
                       "` ~ ୨୧ ·  " + extra + " `\n\n\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝 :` \n\n" +
                       "` ~ ୨୧ ·  " + members + " `\n\n\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " 𝐏𝐥𝐞𝐚𝐬𝐞 𝐜𝐥𝐢𝐜𝐤 𝐭𝐡𝐞 𝐐𝐮𝐞𝐬𝐭 𝐁𝐨𝐚𝐫𝐝 𝐥𝐢𝐧𝐤 𝐛𝐞𝐥𝐨𝐰 𝐭𝐨 𝐑𝐒𝐕𝐏 𝐢𝐟 𝐲𝐨𝐮 𝐚𝐫𝐞 𝐬𝐮𝐫𝐞 𝐲𝐨𝐮 𝐜𝐚𝐧 𝐚𝐭𝐭𝐞𝐧𝐝.. ⑅<a:animehype:1514915354894405702>\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _";

            case "photo":
                return "# ୧ ╰ 𝐏𝐇𝐎𝐓𝐎𝐒𝐇𝐎𝐎𝐓 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ · ` " + time + "\n\n\n" +
                       "`~ ୨୧ ·  𝐒𝐞𝐫𝐯𝐞𝐫 :` \n\n" +
                       "` ~ ୨୧ ·  " + extra + " `\n\n\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝 :` \n\n" +
                       "` ~ ୨୧ ·  " + members + " `\n\n\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " 𝐏𝐥𝐞𝐚𝐬𝐞 𝐜𝐥𝐢𝐜𝐤 𝐭𝐡𝐞 𝐐𝐮𝐞𝐬𝐭 𝐁𝐨𝐚𝐫𝐝 𝐥𝐢𝐧𝐤 𝐛𝐞𝐥𝐨𝐰 𝐭𝐨 𝐑𝐒𝐕𝐏 𝐢𝐟 𝐲𝐨𝐮 𝐚𝐫𝐞 𝐬𝐮𝐫𝐞 𝐲𝐨𝐮 𝐜𝐚𝐧 𝐚𝐭𝐭𝐞𝐧𝐝.. ⑅<a:4_pinkies:1514917024252559392>\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _";

            case "mini_comp":
                return "# ୧ ╰ 𝐌𝐈𝐍𝐈 𝐂𝐎𝐌𝐏𝐄𝐓𝐈𝐓𝐈𝐎𝐍 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ · ` " + time + "\n\n\n" +
                       "`~ ୨୧ ·  𝐒𝐞𝐫𝐯𝐞𝐫 :` \n\n" +
                       "` ~ ୨୧ ·  " + extra + " `\n\n\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝 :` \n\n" +
                       "` ~ ୨୧ ·  " + members + " `\n\n\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " 𝐏𝐥𝐞𝐚𝐬𝐞 𝐜𝐥𝐢𝐜𝐤 𝐭𝐡𝐞 𝐐𝐮𝐞𝐬𝐭 𝐁𝐨𝐚𝐫𝐝 𝐥𝐢𝐧𝐤 𝐛𝐞𝐥𝐨𝐰 𝐭𝐨 𝐑𝐒𝐕𝐏 𝐢𝐟 𝐲𝐨𝐮 𝐚𝐫𝐞 𝐬𝐮𝐫𝐞 𝐲𝐨𝐮 𝐜𝐚𝐧 𝐚𝐭𝐭𝐞𝐧𝐝.. ⑅<a:4_heartpoof:1514918222531399811>\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _";

            case "fashion":
                return "# ୧ ╰ 𝐅𝐀𝐒𝐇𝐈𝐎𝐍 𝐒𝐇𝐎𝐖 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ · ` " + time + "\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐡𝐞𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ ·  " + extra + " `\n\n\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝 :` \n\n" +
                       "` ~ ୨୧ ·  " + members + " `\n\n\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " 𝐏𝐥𝐞𝐚𝐬𝐞 𝐜𝐥𝐢𝐜𝐤 𝐭𝐡𝐞 𝐐𝐮𝐞𝐬𝐭 𝐁𝐨𝐚𝐫𝐝 𝐥𝐢𝐧𝐤 𝐛𝐞𝐥𝐨𝐰 𝐭𝐨 𝐑𝐒𝐕𝐏 𝐢𝐟 𝐲𝐨𝐮 𝐚𝐫𝐞 𝐬𝐮𝐫𝐞 𝐲𝐨𝐮 𝐜𝐚𝐧 𝐚𝐭𝐭𝐞𝐧𝐝.. ⑅<:bunnyyay:1525463988790628373>\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _";

            case "training_comp":
                return "# ୧ ╰ 𝐓𝐑𝐀𝐈𝐍𝐈𝐍𝐆 𝐂𝐎𝐌𝐏 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐫𝐚𝐢𝐧𝐞𝐫 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ · ` " + time + "\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐡𝐞𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ ·  " + extra + " `\n\n\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝 :` \n\n" +
                       "` ~ ୨୧ ·  " + members + " `\n\n\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " 𝐏𝐥𝐞𝐚𝐬𝐞 𝐜𝐥𝐢𝐜𝐤 𝐭𝐡𝐞 𝐐𝐮𝐞𝐬𝐭 𝐁𝐨𝐚𝐫𝐝 𝐥𝐢𝐧𝐤 𝐛𝐞𝐥𝐨𝐰 𝐭𝐨 𝐑𝐒𝐕𝐏 𝐢𝐟 𝐲𝐨𝐮 𝐚𝐫𝐞 𝐬𝐮𝐫𝐞 𝐲𝐨𝐮 𝐜𝐚𝐧 𝐚𝐭𝐭𝐞𝐧𝐝.. ⑅<a:3_x_hearts:1514916224507842622>\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _";

            case "dance_party":
                return "# ୧ ╰ 𝐃𝐀𝐍𝐂𝐄 𝐏𝐀𝐑𝐓𝐘 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ · ` " + time + "\n\n\n" +
                       "`~ ୨୧ ·  𝐒𝐞𝐫𝐯𝐞𝐫 :` \n\n" +
                       "` ~ ୨୧ ·  " + extra + " `\n\n\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝 :` \n\n" +
                       "` ~ ୨୧ ·  " + members + " `\n\n\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " 𝐏𝐥𝐞𝐚𝐬𝐞 𝐜𝐥𝐢𝐜𝐤 𝐭𝐡𝐞 𝐐𝐮𝐞𝐬𝐭 𝐁𝐨𝐚𝐫𝐝 𝐥𝐢𝐧𝐤 𝐛𝐞𝐥𝐨𝐰 𝐭𝐨 𝐑𝐒𝐕𝐏 𝐢𝐟 𝐲𝐨𝐮 𝐚𝐫𝐞 𝐬𝐮𝐫𝐞 𝐲𝐨𝐮 𝐜𝐚𝐧 𝐚𝐭𝐭𝐞𝐧𝐝.. ⑅<a:8_music:1514920332006264953>\n" +
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _";

            default: return "Error building template.";
        }
    }
    private void executeEventPayout(net.dv8tion.jda.api.interactions.InteractionHook hook, net.dv8tion.jda.api.entities.User executor, net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel, String msgId, List<String> excludedIds, boolean isUrgent, String audience) {
        channel.retrieveMessageById(msgId).queue(startMsg -> {
            MessageEmbed embed = startMsg.getEmbeds().get(0);
            int rewardAmount = 0;
            if (embed.getFooter() != null && embed.getFooter().getText() != null) {
                Matcher mReward = Pattern.compile("Reward embedded:\\s*(\\d+)").matcher(embed.getFooter().getText());
                if (mReward.find()) rewardAmount = Integer.parseInt(mReward.group(1));
            }

            String partyData = "None";
            int fieldIndex = -1;
            for (int i = 0; i < embed.getFields().size(); i++) {
                MessageEmbed.Field field = embed.getFields().get(i);
                if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                    partyData = field.getValue();
                    fieldIndex = i;
                    break;
                }
            }

            if (partyData == null || partyData.equals("None") || partyData.isEmpty()) {
                hook.sendMessage("❌ Cannot complete. The party is empty!").queue();
                return;
            }

            List<String> userIds = new ArrayList<>();
            Matcher mUser = Pattern.compile("<@!?(\\d+)>").matcher(partyData);
            while (mUser.find()) userIds.add(mUser.group(1));

            StringBuilder payoutLog = new StringBuilder();
            DatabaseManager db = DatabaseManager.getInstance();

            for (String uid : userIds) {
                if (excludedIds.contains(uid)) {
                    payoutLog.append("• <@").append(uid).append("> was excluded from the payout.\n");
                    continue;
                }
                
                double multiplier = 1.0;
                boolean isComp = false;
                
                net.dv8tion.jda.api.entities.Member member = startMsg.getGuild().getMemberById(uid);
                if (member != null) {
                    if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Spark"))) multiplier = 1.10;
                    else if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Neon"))) multiplier = 1.25;
                    else if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Pulse"))) multiplier = 1.50;
                    else if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Prism"))) multiplier = 1.75;
                    else if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("Supernova"))) multiplier = 2.0;
                    
                    if (member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("AM0RIX"))) {
                        multiplier += 0.50;
                        isComp = true;
                    }
                }
                
                int finalReward = (int) Math.round(rewardAmount * multiplier);
                
                int currentPoints = db.getPoints(uid);
                db.updatePoints(uid, currentPoints + finalReward);
                db.incrementBountyStats(uid, isUrgent);
                
                db.ensureConnected();
                try (java.sql.PreparedStatement pstmt = db.ensureConnected().prepareStatement("UPDATE users SET last_task_date = ? WHERE user_id = ?")) {
                    pstmt.setLong(1, System.currentTimeMillis());
                    pstmt.setString(2, uid);
                    pstmt.executeUpdate();
                } catch (Exception ignored) {}

                String bonusText = (multiplier > 1.0) ? " *(x" + multiplier + " Rank Bonus!)*" : "";
                payoutLog.append("• <@").append(uid).append("> received `").append(finalReward).append(" PTS`").append(bonusText).append("\n");
            }

            EmbedBuilder completedEmbed = new EmbedBuilder(embed);
            completedEmbed.setColor(new Color(50, 205, 50));
            String title = embed.getTitle();
            if (title != null && !title.startsWith("✅")) {
                completedEmbed.setTitle("✅ [COMPLETED] " + title);
            }

            if (fieldIndex != -1) {
                completedEmbed.getFields().remove(fieldIndex);
            }
            completedEmbed.addField(" EVENT FINISHED!", "Successfully completed by the party!\n\n" + payoutLog.toString(), false);

            startMsg.editMessageEmbeds(completedEmbed.build()).setComponents(java.util.Collections.emptyList()).queue();

            if (channel.getType().isThread()) {
                ThreadChannel thread = (ThreadChannel) channel;
                
                String targetPingChannelId = audience.equals("member") ? System.getenv("MEMBER_SCHEDULE_CHANNEL_ID") : System.getenv("SCHEDULE_CHANNEL_ID");
                if (targetPingChannelId != null) {
                    TextChannel textChannel = startMsg.getJDA().getTextChannelById(targetPingChannelId);
                    net.dv8tion.jda.api.entities.channel.concrete.NewsChannel newsChannel = startMsg.getJDA().getNewsChannelById(targetPingChannelId);
                    net.dv8tion.jda.api.entities.channel.middleman.MessageChannel pingChannel = (textChannel != null) ? textChannel : newsChannel;

                    if (pingChannel != null) {
                        String jumpUrl = thread.getJumpUrl();
                        pingChannel.getIterableHistory().takeAsync(100).thenAccept(messages -> {
                            for (Message msg : messages) {
                                if (msg.getAuthor().getId().equals(startMsg.getJDA().getSelfUser().getId()) && msg.getContentRaw().contains(jumpUrl)) {
                                    msg.editMessage("✅ **[THIS EVENT IS COMPLETED]**\n~~" + msg.getContentRaw().replace("~~", "") + "~~").queue();
                                    break;
                                }
                            }
                        });
                    }
                }

                hook.sendMessage(" Payout processing complete! Thread locking and archiving...").queue();
                thread.sendMessage(" **Event Concluded & Paid:** This event has been completed! All eligible party members have received their Points and profile stats.").queue(
                    success -> thread.getManager().setLocked(true).setArchived(true).queue()
                );
            } else {
                hook.sendMessage("✅ Payout processing complete!").queue();
            }
        }, error -> hook.sendMessage("❌ Error fetching the event message.").queue());
    }
    public static long parseEventEpoch(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) return 0;
        String text = rawTime.trim();

        try {
            Matcher dateMatcher = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").matcher(text);
            if (!dateMatcher.find()) return 0;
            int year = Integer.parseInt(dateMatcher.group(1));
            int month = Integer.parseInt(dateMatcher.group(2));
            int day = Integer.parseInt(dateMatcher.group(3));

            Matcher timeMatcher = Pattern.compile("(?i)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(text);
            if (!timeMatcher.find()) return 0;
            int hour = Integer.parseInt(timeMatcher.group(1));
            int min = Integer.parseInt(timeMatcher.group(2));
            String ampm = timeMatcher.group(4);
            if (ampm != null) {
                if (ampm.equalsIgnoreCase("pm") && hour < 12) hour += 12;
                else if (ampm.equalsIgnoreCase("am") && hour == 12) hour = 0;
            }

            ZoneId zoneId = ZoneOffset.UTC;
            String upper = text.toUpperCase();

            Matcher offsetMatcher = Pattern.compile("(?:GMT|UTC)?\\s*([+-])\\s*(\\d{1,2})(?::(\\d{2})|\\.(\\d+))?").matcher(upper);
            if (offsetMatcher.find()) {
                String sign = offsetMatcher.group(1);
                int offsetHours = Integer.parseInt(offsetMatcher.group(2));
                int offsetMins = 0;
                if (offsetMatcher.group(3) != null) {
                    offsetMins = Integer.parseInt(offsetMatcher.group(3));
                } else if (offsetMatcher.group(4) != null) {
                    double frac = Double.parseDouble("0." + offsetMatcher.group(4));
                    offsetMins = (int) Math.round(frac * 60);
                }
                if (sign.equals("-")) {
                    offsetHours = -offsetHours;
                    offsetMins = -offsetMins;
                }
                zoneId = ZoneOffset.ofHoursMinutes(offsetHours, offsetMins);
            } else if (upper.contains("EST")) zoneId = ZoneOffset.of("-05:00");
            else if (upper.contains("EDT")) zoneId = ZoneOffset.of("-04:00");
            else if (upper.contains("CST")) zoneId = ZoneOffset.of("-06:00");
            else if (upper.contains("CDT")) zoneId = ZoneOffset.of("-05:00");
            else if (upper.contains("PST")) zoneId = ZoneOffset.of("-08:00");
            else if (upper.contains("PDT")) zoneId = ZoneOffset.of("-07:00");
            else if (upper.contains("JST") || upper.contains("KST")) zoneId = ZoneOffset.of("+09:00");
            else if (upper.contains("ICT")) zoneId = ZoneOffset.of("+07:00");
            else if (upper.contains("IST")) zoneId = ZoneOffset.of("+05:30");
            else if (upper.contains("BST") || upper.contains("CET")) zoneId = ZoneOffset.of("+01:00");
            else if (upper.contains("CEST")) zoneId = ZoneOffset.of("+02:00");
            else if (upper.contains("AEST")) zoneId = ZoneOffset.of("+10:00");

            ZonedDateTime zdt = ZonedDateTime.of(year, month, day, hour, min, 0, 0, zoneId);
            return zdt.toEpochSecond();
        } catch (Exception e) {
            return 0;
        }
    }

    public static int[] parseSlotConfig(String input) {
        if (input == null || input.isBlank()) return new int[]{0, 0};
        String s = input.trim();

        int min = 0;
        int max = 0;

        Matcher minM = Pattern.compile("(?i)min(?:imum)?\\s*[:=]?\\s*(\\d+)").matcher(s);
        if (minM.find()) min = Integer.parseInt(minM.group(1));

        Matcher maxM = Pattern.compile("(?i)max(?:imum)?\\s*[:=]?\\s*(\\d+)").matcher(s);
        if (maxM.find()) max = Integer.parseInt(maxM.group(1));

        if (min == 0 && max == 0) {
            Matcher slashM = Pattern.compile("(\\d+)\\s*(?:/|-|\\s+to\\s+)\\s*(\\d+|unlimited|none|0)?", Pattern.CASE_INSENSITIVE).matcher(s);
            if (slashM.find()) {
                min = Integer.parseInt(slashM.group(1));
                String second = slashM.group(2);
                if (second != null && !second.equalsIgnoreCase("unlimited") && !second.equalsIgnoreCase("none") && !second.equals("0")) {
                    try { max = Integer.parseInt(second); } catch (Exception ignored) {}
                }
            } else {
                Matcher single = Pattern.compile("(\\d+)").matcher(s);
                if (single.find()) {
                    min = Integer.parseInt(single.group(1));
                }
            }
        }

        return new int[]{min, max};
    }

    public static String buildPartyFieldTitle(int current, int min, int max) {
        String maxDisplay = (max <= 0) ? "Unlimited" : String.valueOf(max);
        StringBuilder sb = new StringBuilder("👥 Party [" + current + "/" + maxDisplay + "]");
        if (max > 0 && current >= max) {
            sb.append(" • 🟡 Full");
        } else if (min > 0 && current >= min) {
            sb.append(" •  Min Met (" + min + ")");
        } else if (min > 0) {
            sb.append(" •  Need " + (min - current) + " more");
        }
        return sb.toString();
    }
    
}