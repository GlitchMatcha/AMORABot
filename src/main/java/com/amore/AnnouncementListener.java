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
        handleScheduleReaction(event.getJDA(), event.getChannel(), event.getMessageId(), event.getUserId(), event.getMember(), event.getReaction(), true);
    }

    @Override
    public void onMessageReactionRemove(net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent event) {
        handleScheduleReaction(event.getJDA(), event.getChannel(), event.getMessageId(), event.getUserId(), null, null, false);
    }

    private void handleScheduleReaction(net.dv8tion.jda.api.JDA jda, net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel, String messageId, String userId, net.dv8tion.jda.api.entities.Member member, net.dv8tion.jda.api.entities.MessageReaction reaction, boolean isAdding) {
        if (userId == null || userId.equals(jda.getSelfUser().getId())) return;

        DatabaseManager db = DatabaseManager.getInstance();
        String[] syncData = db.getEventSync(messageId);
        if (syncData == null) return;

        String audience = syncData[2];

        if (isAdding && audience.equals("member")) {
            if (!CommandListener.isOfficialMember(member)) {
                if (reaction != null) reaction.removeReaction(jda.getUserById(userId)).queue();
                return;
            }
        }

        channel.retrieveMessageById(messageId).queue(startMsg -> {
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
            ThreadChannel thread = startMsg.getStartedThread();
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
                int added = changeParty(msg, event.getMentions().getUsers(), true);
                if (added < 0) {
                    event.reply("❌ Party data missing!").setEphemeral(true).queue();
                } else if (added == 0) {
                    event.reply("⚠️ No new members were added (Party might be full, or they are already in!).").setEphemeral(true).queue();
                } else {
                    event.reply("✅ Successfully added " + added + " member(s) to the party!").setEphemeral(true).queue();
                }
            });
            return;
        }

        if (componentId.startsWith("select_kick_")) {
            String msgId = componentId.substring("select_kick_".length());
            event.getChannel().retrieveMessageById(msgId).queue(msg -> {
                int removed = changeParty(msg, event.getMentions().getUsers(), false);
                if (removed < 0) {
                    event.reply("❌ Party data missing!").setEphemeral(true).queue();
                } else if (removed == 0) {
                    event.reply("⚠️ None of the selected users were in the party.").setEphemeral(true).queue();
                } else {
                    event.reply("✅ Successfully removed " + removed + " member(s) from the party!").setEphemeral(true).queue();
                }
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
            List<String> attended = new ArrayList<>(userIds);
            attended.removeAll(excludedIds);
            recordAttendance(message, embed, attended, rewardAmount);
            
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
        long currentEpoch = System.currentTimeMillis() / 1000;
        if (unixEpoch > 0 && unixEpoch < currentEpoch - 60) {
            event.reply("⚠️ **Invalid Time:** The time you entered (`" + rawTime + "`) is in the past! Please enter a future time for this event.").setEphemeral(true).queue();
            return;
        }

        int reward = 0;
        try { reward = Integer.parseInt(event.getValue("input_reward").getAsString().trim()); } catch (Exception ignored) {}
        String extra = event.getValue("input_extra") != null ? event.getValue("input_extra").getAsString() : "";

        String partyField = "None";
        int currentSlots = 0;

        if (isEdit && event.getMessage() != null && !event.getMessage().getEmbeds().isEmpty()) {
            String[] party = currentParty(event.getMessage().getEmbeds().get(0));
            partyField = party[0];
            currentSlots = Integer.parseInt(party[1]);
        }

        EventPost post = buildEventPost(type, audience, urgency, host, slots, rawTime, unixEpoch, reward, extra, partyField, currentSlots);

        if (isEdit) {
            event.editMessage(MessageEditData.fromCreateData(post.data())).queue();
            String threadId = event.getMessage().getId(); 
            scheduleTimers(threadId, post.unixEpoch(), event.getJDA());
            event.getHook().sendMessage("✅ **Event Details Updated!**").setEphemeral(true).queue();
        } else {
            // 🚨 SINGLE CHANNEL ROUTING (NO MORE FORUMS)
            if (scheduleChannelId(audience) == null) {
                event.reply("⚠️ **Routing Error:** Missing Environment Variables! Ensure `MEMBER_SCHEDULE_CHANNEL_ID` and `SCHEDULE_CHANNEL_ID` are set in the .env file.").setEphemeral(true).queue();
                return;
            }
            net.dv8tion.jda.api.entities.channel.middleman.MessageChannel pingChannel = scheduleChannel(event.getJDA(), audience);
            if (pingChannel == null) {
                event.reply("⚠️ Routing Error: Could not find the target schedules channel!").setEphemeral(true).queue();
                return;
            }

            event.deferReply(true).queue();
            postEvent(pingChannel, post, audience, event.getJDA()).whenComplete((msg, error) -> {
                if (error == null) {
                    event.getHook().sendMessage(" Event successfully created successfully into Schedules!").queue();
                } else if (error instanceof EventThreadFailed) {
                    event.getHook().sendMessage(" Event posted, but failed to create a discussion thread!").queue();
                } else {
                    event.getHook().sendMessage(" Error creating event: " + error.getMessage()).queue();
                }
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared event code: the Discord buttons / modals above and the web dashboard (DashboardQueue)
    // ---------------------------------------------------------------------------------------------

    static final List<String> EVENT_TYPES = List.of("training", "movie", "game", "photo", "mini_comp", "fashion",
            "training_comp", "dance_party");

    /** One event post as the /eventsetup modal builds it: message, thread title, start time (0 = unparsed). */
    record EventPost(net.dv8tion.jda.api.utils.messages.MessageCreateData data, String displayTitle, long unixEpoch) { }

    /** The party field's text and head count on an event post ({"None", "0"} when it has none). */
    static String[] currentParty(MessageEmbed embed) {
        for (MessageEmbed.Field field : embed.getFields()) {
            if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                Matcher m = Pattern.compile("\\[(\\d+)/").matcher(field.getName());
                return new String[]{field.getValue(), m.find() ? m.group(1) : "0"};
            }
        }
        return new String[]{"None", "0"};
    }

    /**
     * Edit Details from the web dashboard: rebuilds the post exactly like the Edit button's modal (party kept,
     * reminders re-armed for the new time). Returns null when done, else why it can't.
     */
    String editEvent(Message msg, String host, String slots, String rawTime, String extra, int reward) {
        String[] kind = null;
        for (net.dv8tion.jda.api.interactions.components.buttons.Button button : msg.getButtons()) {
            String id = button.getId();
            if (id != null && id.startsWith("edit_event:")) {
                kind = id.substring("edit_event:".length()).split(":");
                break;
            }
        }
        if (kind == null || kind.length < 3) return "That post has no Edit Details button, so its event type is unknown.";
        long unixEpoch = parseEventEpoch(rawTime);
        if (unixEpoch > 0 && unixEpoch < System.currentTimeMillis() / 1000 - 60) return "That time is in the past.";
        String[] party = currentParty(msg.getEmbeds().get(0));
        EventPost post = buildEventPost(kind[0], kind[1], kind[2], host, slots, rawTime, unixEpoch, reward, extra,
                party[0], Integer.parseInt(party[1]));
        msg.editMessage(MessageEditData.fromCreateData(post.data())).complete();
        scheduleTimers(msg.getId(), post.unixEpoch(), msg.getJDA());
        return null;
    }

    /**
     * Add Member / Kick Member (the menus on the post, and the web dashboard): returns how many people changed, or
     * -1 when the post has no party field. Kicked people's RSVP reaction is cleared so they can re-join later.
     */
    static int changeParty(Message msg, List<net.dv8tion.jda.api.entities.User> users, boolean add) {
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
        if (fieldIndex == -1) return -1;

        int max = maxStr.equalsIgnoreCase("Unlimited") ? Integer.MAX_VALUE : Integer.parseInt(maxStr);
        int changed = 0;
        if (add) {
            if (partyField.equals("None")) partyField = "";
            for (net.dv8tion.jda.api.entities.User u : users) {
                String mention = u.getAsMention();
                if (!partyField.contains(mention)) {
                    if (current >= max) break;
                    partyField += (partyField.isEmpty() ? "" : "\n") + mention;
                    current++;
                    changed++;
                }
            }
        } else {
            for (net.dv8tion.jda.api.entities.User u : users) {
                String mention = u.getAsMention();
                if (partyField.contains(mention)) {
                    partyField = partyField.replace(mention + "\n", "").replace("\n" + mention, "").replace(mention, "");
                    current--;
                    changed++;
                }
            }
            if (partyField.trim().isEmpty()) partyField = "None";
        }
        if (changed == 0) return 0;

        newEmbed.getFields().remove(fieldIndex);
        newEmbed.addField("👥 Party [" + current + "/" + maxStr + "]", partyField, false);
        if (add && current >= max) newEmbed.setColor(Color.YELLOW);
        msg.editMessageEmbeds(newEmbed.build()).queue();

        if (!add) {
            // Joining is reaction-driven: clear the kicked users' RSVP reaction so they can re-react later
            String[] syncData = DatabaseManager.getInstance().getEventSync(msg.getId());
            if (syncData != null && syncData[1] != null) {
                net.dv8tion.jda.api.entities.emoji.Emoji rsvpEmoji = net.dv8tion.jda.api.entities.emoji.Emoji.fromFormatted(syncData[1]);
                for (net.dv8tion.jda.api.entities.User u : users) {
                    msg.removeReaction(rsvpEmoji, u).queue(s -> {}, e -> {});
                }
            }
        }
        return changed;
    }

    /** The schedule post stays, but its discussion thread couldn't be created. */
    static final class EventThreadFailed extends RuntimeException {
        final Message message;
        EventThreadFailed(Message message) {
            super("Event posted, but failed to create a discussion thread");
            this.message = message;
        }
    }

    static String scheduleChannelId(String audience) {
        return audience.equals("member") ? System.getenv("MEMBER_SCHEDULE_CHANNEL_ID") : System.getenv("SCHEDULE_CHANNEL_ID");
    }

    static net.dv8tion.jda.api.entities.channel.middleman.MessageChannel scheduleChannel(net.dv8tion.jda.api.JDA jda, String audience) {
        String id = scheduleChannelId(audience);
        if (id == null) return null;
        TextChannel textChannel = jda.getTextChannelById(id);
        NewsChannel newsChannel = jda.getNewsChannelById(id);
        return (textChannel != null) ? textChannel : newsChannel;
    }

    EventPost buildEventPost(String type, String audience, String urgency, String host, String slots, String rawTime,
                             long unixEpoch, int reward, String extra, String partyField, int currentSlots) {
        String displayTime;
        if (unixEpoch > 0) {
            displayTime = "<t:" + unixEpoch + ":F>\n` ~ ୨୧ · ` <t:" + unixEpoch + ":R>";
        } else {
            // Fallback display if an unparseable text format was entered
            displayTime = "` " + rawTime + " `"; 
        }

        int[] slotConfig = parseSlotConfig(slots);
        int minSlots = slotConfig[0];
        int maxSlots = slotConfig[1];

        String minHeaderDisplay = (minSlots > 0 ? String.valueOf(minSlots) : "1") + 
                                  (maxSlots > 0 ? " (Max: " + maxSlots + ")" : " or more");

        String aestheticHeader = buildTemplateHeader(type, host, displayTime, extra, minHeaderDisplay);

        String displayTitle = (audience.equals("member") ? (urgency.equals("urgent") ? "🚨 " : "👑 ") : "🌍 ") + type.replace("_", " ").toUpperCase();

        Color embedColor = urgency.equals("urgent") ? new Color(220, 20, 60) : 
                           audience.equals("member") ? new Color(255, 215, 0) : new Color(255, 69, 0);

        String partyFieldTitle = buildPartyFieldTitle(currentSlots, minSlots, maxSlots);

        EmbedBuilder questEmbed = new EmbedBuilder()
                .setColor(embedColor)
                .setTitle(displayTitle)
                .setDescription("💰 **Bounty Reward:** `" + reward + " Points` _(Per Person)_\n" +
                                (audience.equals("member") ? "\n⚠️ **Role Requirement:** Official Members Only!\n" : "") +
                                "\n-# use the ✋ emoji below to join or unreact to leave")
                .addField(partyFieldTitle, partyField, false)
                .setFooter("AMORA Event Directive • Reward embedded: " + reward + " • Min: " + minSlots + " • Max: " + maxSlots + " • Time: " + rawTime, null);

        // 🚨 CUSTOM PING LOGIC (AMORA, AMORIX, GUEST)
        String memberRoleId = System.getenv("MEMBER_ROLE_ID");
        String amorixRoleId = System.getenv("AMORIX_ROLE_ID");
        String guestRoleId = System.getenv("GUEST_ROLE_ID");

        StringBuilder pingBuilder = new StringBuilder();
        if (audience.equals("member")) {
            if (memberRoleId != null && !memberRoleId.isBlank()) pingBuilder.append("<@&").append(memberRoleId).append("> ");
            if (amorixRoleId != null && !amorixRoleId.isBlank()) pingBuilder.append("<@&").append(amorixRoleId).append("> ");
            if (pingBuilder.length() == 0) pingBuilder.append("**[Members Only]**");
        } else {
            if (memberRoleId != null && !memberRoleId.isBlank()) pingBuilder.append("<@&").append(memberRoleId).append("> ");
            if (amorixRoleId != null && !amorixRoleId.isBlank()) pingBuilder.append("<@&").append(amorixRoleId).append("> ");
            if (guestRoleId != null && !guestRoleId.isBlank()) pingBuilder.append("<@&").append(guestRoleId).append("> ");
            if (pingBuilder.length() == 0) pingBuilder.append("@everyone");
        }
        String pingMention = pingBuilder.toString().trim();

        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent(aestheticHeader + "\n\n" + pingMention + " . 00 . > Amora < . <3.");
        builder.addEmbeds(questEmbed.build());
        
        builder.addActionRow(
                Button.secondary("alert_party", " Alert the Current Team "),
                Button.primary("view_board", "📋 List of Active Events")
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
        return new EventPost(builder.build(), displayTitle, unixEpoch);
    }

    /** Posts a new event in the schedules channel, opens its thread, starts reminders, ✋ RSVP, event_sync. */
    java.util.concurrent.CompletableFuture<Message> postEvent(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel pingChannel,
                                                             EventPost post, String audience, net.dv8tion.jda.api.JDA jda) {
        java.util.concurrent.CompletableFuture<Message> done = new java.util.concurrent.CompletableFuture<>();
        pingChannel.sendMessage(post.data()).queue(
            msg -> {
                // Create a discussion thread explicitly attached to this schedule message
                msg.createThreadChannel(post.displayTitle()).queue(thread -> {
                    scheduleTimers(thread.getId(), post.unixEpoch(), jda);
                    
                    String defaultEmoji = "✋";
                    msg.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromFormatted(defaultEmoji)).queue();
                    DatabaseManager.getInstance().saveEventSync(msg.getId(), thread.getId(), defaultEmoji, audience);
                    done.complete(msg);
                }, error -> done.completeExceptionally(new EventThreadFailed(msg)));
            }, 
            done::completeExceptionally
        );
        return done;
    }

    /** The closed version of an event embed (🔒 Cancel / Close). */
    static MessageEmbed closedEventEmbed(MessageEmbed embed) {
        EmbedBuilder closedEmbed = new EmbedBuilder(embed);
        closedEmbed.setColor(Color.DARK_GRAY);
        String title = embed.getTitle();
        if (title != null && !title.startsWith("🔒")) {
            closedEmbed.setTitle("🔒 [CLOSED/CANCELLED] " + title);
        }
        return closedEmbed.build();
    }

    /** After closing: the post deletes itself in 2 h and its thread gets the closing note and is locked. */
    static void finishClosedEvent(Message message) {
        message.delete().queueAfter(2, TimeUnit.HOURS);

        ThreadChannel thread = message.getGuild().getThreadChannelById(message.getId());
        if (thread != null) {
            thread.sendMessage("🔒 **Event Concluded/Cancelled:** This quest thread has been officially closed by Staff without payouts.\n\n*(🗑️ **WARNING:** This ticket and thread will auto-delete in exactly 2 hours to keep the schedules channel clean!)*").queue(
                success -> thread.getManager().setLocked(true).setArchived(true).queue()
            );
        }
    }

    /** "<@a> <@b> " for the party on an event embed; null when there's no party field, "" when it's empty. */
    static String partyPings(MessageEmbed embed) {
        String partyField = null;
        for (MessageEmbed.Field field : embed.getFields()) {
            if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                partyField = field.getValue();
                break;
            }
        }
        if (partyField == null) return null;
        if (partyField.equals("None") || partyField.isEmpty()) return "";
        StringBuilder pings = new StringBuilder();
        Matcher m = Pattern.compile("<@!?(\\d+)>").matcher(partyField);
        while (m.find()) pings.append("<@").append(m.group(1)).append("> ");
        return pings.toString();
    }

    /** Who attended a paid event (training sheets on the web dashboard read event_attendance). */
    static void recordAttendance(Message eventMsg, MessageEmbed embed, List<String> userIds, int reward) {
        if (userIds.isEmpty()) return;
        String title = embed.getTitle() == null ? "" : embed.getTitle();
        String bare = title.replace("✅ [COMPLETED] ", "");
        String audience = bare.contains("🌍") ? "everyone" : "member";
        String type = bare.replaceAll("^[^A-Za-z]+", "").trim().toLowerCase().replace(" ", "_");
        String host = null;
        Matcher mHost = Pattern.compile("(?i)(?:𝐓𝐫𝐚𝐢𝐧𝐞𝐫|𝐇𝐨𝐬𝐭)\\s*:`?\\s*\\n+\\s*`\\s*~\\s*୨୧\\s*·\\s*(.*?)\\s*`").matcher(eventMsg.getContentRaw());
        if (mHost.find()) host = mHost.group(1).trim();
        DatabaseManager.getInstance().recordEventAttendance(eventMsg.getId(), userIds, type, audience, bare, host, reward);
    }

    // ----- 📋 List of Active Events (same rule as the web dashboard, amora_tools.event_phase) -----
    static final int BOARD_SCAN = 200;                    // newest messages read in the schedules channel
    static final long LIVE_SECONDS = 3 * 3600;            // an event counts as happening for 3 h after its start
    static final long UNDATED_SECONDS = 7 * 86400;        // a post without a readable time stops counting after a week
    private static final Pattern START_STAMP = Pattern.compile("<t:(\\d{9,11}):F>");
    private static final Pattern FOOTER_TIME = Pattern.compile("Time:\\s*(.+)");
    private static final Pattern FOOTER_MIN = Pattern.compile("Min:\\s*(\\d+)");
    private static final Pattern FOOTER_MAX = Pattern.compile("Max:\\s*(\\d+)");
    private static final Pattern HOST_LINE = Pattern.compile("(?i)(?:𝐓𝐫𝐚𝐢𝐧𝐞𝐫|𝐇𝐨𝐬𝐭)\\s*:`?\\s*\\n+\\s*`\\s*~\\s*୨୧\\s*·\\s*(.*?)\\s*`");

    /** An open event post on the board. start = 0 when the time couldn't be read. */
    record BoardEvent(String title, String url, String host, long start, String rawTime, int joined, int min, int max) {}

    /** The exact start AM0RA wrote into the post (also after Edit Details); else the typed footer time; 0 = unknown. */
    static long eventStart(Message msg, MessageEmbed embed) {
        Matcher stamp = START_STAMP.matcher(msg.getContentRaw());
        if (stamp.find()) return Long.parseLong(stamp.group(1));
        Matcher typed = FOOTER_TIME.matcher(embed.getFooter().getText());
        return typed.find() ? parseEventEpoch(typed.group(1).trim()) : 0;
    }

    /** live / upcoming / overdue (start + 3 h passed, never finished or closed) / undated. */
    static String eventPhase(long start, long posted, long now) {
        if (start <= 0) return posted > now - UNDATED_SECONDS ? "undated" : "overdue";
        if (start > now) return "upcoming";
        return now < start + LIVE_SECONDS ? "live" : "overdue";
    }

    private static int footerInt(Pattern p, String footer) {
        Matcher m = p.matcher(footer);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static MessageEmbed activeBoard(List<Message> messages, String selfId, boolean staff) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, List<BoardEvent>> phases = new java.util.LinkedHashMap<>();
        for (String key : List.of("live", "upcoming", "undated", "overdue")) phases.put(key, new ArrayList<>());

        for (Message msg : messages) {
            if (!msg.getAuthor().getId().equals(selfId) || msg.getEmbeds().isEmpty()) continue;
            MessageEmbed em = msg.getEmbeds().get(0);
            String title = em.getTitle();
            String footer = em.getFooter() == null ? null : em.getFooter().getText();
            if (title == null || footer == null || !footer.startsWith("AMORA Event Directive")) continue;
            if (title.startsWith("✅") || title.startsWith("🔒")) continue;

            Matcher mHost = HOST_LINE.matcher(msg.getContentRaw());
            String host = mHost.find() ? mHost.group(1).trim() : "Unknown";
            Matcher mTime = FOOTER_TIME.matcher(footer);
            String rawTime = mTime.find() ? mTime.group(1).trim() : "";
            String pings = partyPings(em);
            int joined = pings == null || pings.isBlank() ? 0
                    : (int) java.util.Arrays.stream(pings.trim().split("\\s+")).distinct().count();
            long start = eventStart(msg, em);
            BoardEvent ev = new BoardEvent(title, msg.getJumpUrl(), host, start, rawTime, joined,
                    footerInt(FOOTER_MIN, footer), footerInt(FOOTER_MAX, footer));
            phases.get(eventPhase(start, msg.getTimeCreated().toEpochSecond(), now)).add(ev);
        }
        phases.get("live").sort(java.util.Comparator.comparingLong(BoardEvent::start));
        phases.get("upcoming").sort(java.util.Comparator.comparingLong(BoardEvent::start));

        StringBuilder desc = new StringBuilder();
        int shown = boardSection(desc, "🔴 **Happening now**", phases.get("live"));
        shown += boardSection(desc, "📅 **Coming up**", phases.get("upcoming"));
        shown += boardSection(desc, "❓ **Time not set** _(ask the host!)_", phases.get("undated"));
        if (shown == 0) desc.append("_There are no events open to join right now. Check back soon!_ 💖\n\n");

        List<BoardEvent> overdue = phases.get("overdue");
        if (staff && !overdue.isEmpty()) {
            desc.append("━━━━━━━━━━━━━━━━\n");
            boardSection(desc, "⚠️ **Past due, still open** _(only staff see this)_\n"
                    + "-# Started over 3 h ago but never finished or closed. Finish & Payout if it happened, "
                    + "otherwise Cancel / Close.", overdue);
        }

        return new EmbedBuilder()
                .setTitle("✦ AMORA - LIST OF ACTIVE EVENTS ✦")
                .setColor(new Color(255, 182, 193))
                .setDescription(desc.toString().trim())
                .setFooter("Times show in your own timezone • an event stays \"happening now\" for 3 hours after it starts")
                .build();
    }

    /** Appends a heading and one entry per event, keeping the embed under Discord's 4096-character limit. */
    private static int boardSection(StringBuilder desc, String heading, List<BoardEvent> events) {
        if (events.isEmpty()) return 0;
        desc.append(heading).append("\n");
        int added = 0;
        for (BoardEvent ev : events) {
            StringBuilder line = new StringBuilder("🔹 **[").append(ev.title()).append("](").append(ev.url()).append(")**\n")
                    .append("👤 ").append(ev.host()).append(" · 🕒 ");
            if (ev.start() > 0) line.append("<t:").append(ev.start()).append(":f> (<t:").append(ev.start()).append(":R>)");
            else line.append(ev.rawTime().isEmpty() ? "no time given" : "`" + ev.rawTime() + "`");
            line.append(" · 👥 ").append(ev.joined()).append(ev.max() > 0 ? "/" + ev.max() : "").append(" joined");
            if (ev.max() > 0 && ev.joined() >= ev.max()) line.append(" · **FULL**");
            else if (ev.min() > ev.joined()) line.append(" · needs ").append(ev.min() - ev.joined()).append(" more");
            line.append("\n");
            int left = events.size() - added;
            if (desc.length() + line.length() > 3900) {
                desc.append("_…and ").append(left).append(" more_\n");
                break;
            }
            desc.append(line);
            added++;
        }
        desc.append("\n");
        return added;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();

        if (buttonId.equals("view_board")) {
            boolean staff = event.getMember() != null
                    && event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR);
            String selfId = event.getJDA().getSelfUser().getId();
            event.deferReply(true).queue(hook ->
                event.getChannel().getIterableHistory().takeAsync(BOARD_SCAN)
                    .thenAccept(messages -> hook.sendMessageEmbeds(activeBoard(messages, selfId, staff)).queue())
                    .exceptionally(err -> {
                        hook.sendMessage("⚠️ Couldn't read the events right now. Please try again in a moment.").queue();
                        return null;
                    }));
            return;
        }

        if (buttonId.startsWith("event_complete:")) {
            if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
                event.reply(" Director clearance required to authorize payouts!").setEphemeral(true).queue();
                return;
            }
            MessageEmbed embed = event.getMessage().getEmbeds().isEmpty() ? null : event.getMessage().getEmbeds().get(0);
            int minRequired = 0;
            
            if (embed != null && embed.getFooter() != null && embed.getFooter().getText() != null) {
                String footerText = embed.getFooter().getText();
                // The exact start written into the post (any typed time format), not just "yyyy-MM-dd HH:mm z"
                long eventEpoch = eventStart(event.getMessage(), embed);
                if (eventEpoch > 0 && (System.currentTimeMillis() / 1000) < eventEpoch) {
                    event.reply("⚠️ **The event hasn't started yet!**\nYou cannot complete and payout an event that is still in the future. To cancel or close this event early without payouts, please click the 🔒 **Cancel / Close** button instead.").setEphemeral(true).queue();
                    return;
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
                String amorixRoleId = System.getenv("AMORIX_ROLE_ID");

                String pingMention;
                if (audience.equals("member")) {
                    StringBuilder sb = new StringBuilder();
                    if (memberRoleId != null && !memberRoleId.isBlank()) sb.append("<@&").append(memberRoleId.trim()).append("> ");
                    if (amorixRoleId != null && !amorixRoleId.isBlank()) sb.append("<@&").append(amorixRoleId.trim()).append("> ");
                    pingMention = sb.length() > 0 ? sb.toString().trim() : "**[Official Members]**";
                } else {
                    pingMention = "@everyone";
                }
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

            Message message = event.getMessage();
            MessageEmbed embed = message.getEmbeds().isEmpty() ? null : message.getEmbeds().get(0);
            
            if (embed == null) return;

            event.editMessageEmbeds(closedEventEmbed(embed)).setComponents(java.util.Collections.emptyList()).queue();
            finishClosedEvent(message);
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
        
        if (buttonId.equals("alert_party")) {
            if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.MESSAGE_MANAGE)) {
                event.reply(" **Access Denied:** Only Staff can manually alert the team!").setEphemeral(true).queue();
                return;
            }

            String pings = partyPings(event.getMessage().getEmbeds().get(0));
            if (pings == null) {
                event.reply(" Error: Could not read party data.").setEphemeral(true).queue();
                return;
            }
            if (pings.isEmpty()) {
                event.reply(" The team is currently empty!").setEphemeral(true).queue();
                return;
            }

            event.getMessage().getChannel().sendMessage(" **MANUAL STAFF ALERT:**\n" + pings).queue();
            event.reply(" The team has been successfully alerted!").setEphemeral(true).queue();
        }
    }

    private Modal buildEventModal(String idPrefix, String title, String type, String audience, String urgency, 
                                  String defaultHost, String defaultTime, String defaultExtra, String defaultSlots, String defaultReward) {
        
        TextInput.Builder hostBuilder = TextInput.create("input_host", "Host / Trainer", TextInputStyle.SHORT)
                .setPlaceholder("Example: @Matcha or Username").setRequired(true);
        if (type.equals("training") || type.equals("training_comp")) hostBuilder.setLabel("Trainer");
        if (!defaultHost.isBlank()) hostBuilder.setValue(defaultHost);

        TextInput.Builder timeBuilder = TextInput.create("input_time", "Date & Time (Include Timezone)", TextInputStyle.SHORT)
                .setPlaceholder("yyyy-mm-dd hh:mm timezone (GMT+7, PT, EEST, America/Los_Angeles)").setRequired(true);
        if (!defaultTime.isBlank()) timeBuilder.setValue(defaultTime);

        TextInput.Builder slotsBuilder = TextInput.create("input_slots", "Party Capacity (Min / Max)", TextInputStyle.SHORT)
                .setPlaceholder("Example: Min 2 Max 10 (or just 2 / Unlimited)").setRequired(true);
        if (!defaultSlots.isBlank()) slotsBuilder.setValue(defaultSlots);

        TextInput.Builder rewardBuilder = TextInput.create("input_reward", "Reward (Points per person)", TextInputStyle.SHORT)
                .setPlaceholder("Example: 50 (Just type the number)").setRequired(true);
        if (!defaultReward.isBlank()) rewardBuilder.setValue(defaultReward);

        Modal.Builder modal = Modal.create(idPrefix + type + ":" + audience + ":" + urgency, title);
        modal.addActionRow(hostBuilder.build());
        modal.addActionRow(timeBuilder.build());

        if (type.equals("game")) {
            TextInput.Builder extraBuilder = TextInput.create("input_extra", "Games", TextInputStyle.SHORT).setRequired(true)
                .setPlaceholder("Example: Roblox Uno, Gartic Phone, etc.");
            if (!defaultExtra.isBlank()) extraBuilder.setValue(defaultExtra);
            modal.addActionRow(extraBuilder.build());
        } else if (type.equals("fashion") || type.equals("training_comp")) {
            TextInput.Builder extraBuilder = TextInput.create("input_extra", "Theme", TextInputStyle.SHORT).setRequired(true)
                .setPlaceholder("Example: Cyberpunk, Y2K, Fairycore...");
            if (!defaultExtra.isBlank()) extraBuilder.setValue(defaultExtra);
            modal.addActionRow(extraBuilder.build());
        } else if (type.equals("training")) {
            TextInput.Builder extraBuilder = TextInput.create("input_extra", "Server & Type", TextInputStyle.SHORT).setRequired(true)
                .setPlaceholder("Example: VIP Server - Basic Lyrics");
            if (!defaultExtra.isBlank()) extraBuilder.setValue(defaultExtra);
            modal.addActionRow(extraBuilder.build());
        } else if (!type.equals("movie")) {
            TextInput.Builder extraBuilder = TextInput.create("input_extra", "Server", TextInputStyle.SHORT).setRequired(true)
                .setPlaceholder("Example: VIP Server link or name");
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
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n";
                       
                       

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
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" ;

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
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" ;

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
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" ;

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
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" ;

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
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" ;

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
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" ;

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
                       " ₊ ⊹ ································································ ⊹ ࣪ ˖ \n" ;

            default: return "Error building template.";
        }
    }
    private void executeEventPayout(net.dv8tion.jda.api.interactions.InteractionHook hook, net.dv8tion.jda.api.entities.User executor, net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel, String msgId, List<String> excludedIds, boolean isUrgent, String audience) {
        channel.retrieveMessageById(msgId).queue(startMsg -> {
            PayoutResult result = payoutEvent(startMsg, excludedIds, isUrgent);
            if (result.error() != null) {
                hook.sendMessage(result.error()).queue();
            } else if (result.thread() != null) {
                hook.sendMessage("✅ **Payout processing complete!** Detailed payout notification has been posted inside the thread: " + result.thread().getAsMention()).queue();
            } else {
                hook.sendMessage("✅ **Payout processing complete!** Rewards have been distributed to the party.").queue();
            }
        }, error -> hook.sendMessage("❌ Error fetching the event message.").queue());
    }

    /** error = why nothing was paid (or null); paid = [user id, points] per paid member. */
    record PayoutResult(String error, List<String[]> paid, ThreadChannel thread) { }

    /** Pays the party of an event post (rank multipliers), marks it completed, posts the payout in its thread. */
    PayoutResult payoutEvent(Message startMsg, List<String> excludedIds, boolean isUrgent) {
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
            return new PayoutResult("❌ Cannot complete. The party is empty!", List.of(), null);
        }

        List<String> userIds = new ArrayList<>();
        Matcher mUser = Pattern.compile("<@!?(\\d+)>").matcher(partyData);
        while (mUser.find()) userIds.add(mUser.group(1));

        StringBuilder payoutLog = new StringBuilder();
        List<String[]> paid = new ArrayList<>();
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
            paid.add(new String[]{uid, String.valueOf(finalReward)});
        }
        recordAttendance(startMsg, embed, paid.stream().map(p -> p[0]).toList(), rewardAmount);

        EmbedBuilder completedEmbed = new EmbedBuilder(embed);
        completedEmbed.setColor(new Color(50, 205, 50));
        String title = embed.getTitle();
        if (title != null && !title.startsWith("✅")) {
            completedEmbed.setTitle("✅ [COMPLETED] " + title);
        }

        if (fieldIndex != -1) {
            completedEmbed.getFields().remove(fieldIndex);
        }
        completedEmbed.addField("✅ Event Concluded & Paid", "All Points and rank bonuses have been distributed inside the event discussion thread.", false);

        startMsg.editMessageEmbeds(completedEmbed.build()).setComponents(java.util.Collections.emptyList()).queue(msg -> {
            msg.delete().queueAfter(2, TimeUnit.HOURS);
        });

        ThreadChannel resolvedThread = startMsg.getStartedThread();
        if (resolvedThread == null) {
            String[] sync = db.getEventSync(startMsg.getId());
            if (sync != null && sync[0] != null) {
                resolvedThread = startMsg.getGuild().getThreadChannelById(sync[0]);
            }
        }
        final ThreadChannel thread = resolvedThread;
        if (thread != null) {
            EmbedBuilder threadPayoutEmbed = new EmbedBuilder()
                .setColor(new Color(50, 205, 50))
                .setTitle("🎉 ✦ EVENT CONCLUDED & PAYOUT DISTRIBUTED ✦")
                .setDescription("This event has officially ended! Rewards have been credited to everyone in the party:\n\n" + payoutLog.toString())
                .setFooter("AMORA Directives System • Points & tasks updated", null);

            thread.sendMessageEmbeds(threadPayoutEmbed.build()).queue(sent -> {
                thread.getManager().setLocked(true).queue();
            });
        }
        return new PayoutResult(null, paid, thread);
    }
    private static int parseMonthName(String raw) {
        String m = raw.toLowerCase();
        if (m.startsWith("jan")) return 1;
        if (m.startsWith("feb")) return 2;
        if (m.startsWith("mar")) return 3;
        if (m.startsWith("apr")) return 4;
        if (m.startsWith("may")) return 5;
        if (m.startsWith("jun")) return 6;
        if (m.startsWith("jul")) return 7;
        if (m.startsWith("aug")) return 8;
        if (m.startsWith("sep")) return 9;
        if (m.startsWith("oct")) return 10;
        if (m.startsWith("nov")) return 11;
        if (m.startsWith("dec")) return 12;
        return 1;
    }

    // Dates ("2026-10-23", "10/23/2026") and clock times ("20:30") are removed before looking for a zone, otherwise the
    // "-10" in a date was read as a UTC-10 offset. A time directly after a sign ("GMT+5:30") belongs to the offset and stays.
    private static final Pattern DATE_YMD = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}");
    private static final Pattern DATE_DMY = Pattern.compile("\\d{1,2}[-/]\\d{1,2}[-/]\\d{4}");
    private static final Pattern CLOCK_TIME = Pattern.compile("(?<![+\\-\\d:.])\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)?");

    // Region IDs such as "America/Los_Angeles" (matched case-insensitively), which also follow daylight saving.
    private static final Map<String, String> REGION_IDS = new java.util.HashMap<>();
    static {
        for (String id : ZoneId.getAvailableZoneIds()) {
            if (id.contains("/")) REGION_IDS.putIfAbsent(id.toLowerCase(), id);
        }
    }
    private static final Pattern REGION_TOKEN = Pattern.compile("[A-Za-z_]+(?:/[A-Za-z_+\\-0-9]+){1,2}");

    public static ZoneId parseEventZoneId(String text) {
        String cleaned = CLOCK_TIME.matcher(DATE_DMY.matcher(DATE_YMD.matcher(text).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        String upper = cleaned.toUpperCase();

        Matcher regionMatcher = REGION_TOKEN.matcher(cleaned);
        while (regionMatcher.find()) {
            String id = REGION_IDS.get(regionMatcher.group().toLowerCase());
            if (id != null) return ZoneId.of(id);
        }

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
            try {
                return ZoneOffset.ofHoursMinutes(offsetHours, offsetMins);
            } catch (Exception ignored) {}
        }

        // Eastern European Timezones (Fix for Ari)
        if (Pattern.compile("\\bEEST\\b").matcher(upper).find()) return ZoneOffset.of("+03:00");
        if (Pattern.compile("\\bEET\\b").matcher(upper).find()) return ZoneOffset.of("+02:00");

        // Central & Western European
        if (Pattern.compile("\\bCEST\\b").matcher(upper).find()) return ZoneOffset.of("+02:00");
        if (Pattern.compile("\\bCET\\b").matcher(upper).find()) return ZoneOffset.of("+01:00");
        if (Pattern.compile("\\bBST\\b").matcher(upper).find()) return ZoneOffset.of("+01:00");
        if (Pattern.compile("\\bWEST\\b").matcher(upper).find()) return ZoneOffset.of("+01:00");
        if (Pattern.compile("\\bWET\\b").matcher(upper).find()) return ZoneOffset.of("+00:00");
        if (Pattern.compile("\\b(GMT|UTC|Z)\\b").matcher(upper).find()) return ZoneOffset.UTC;

        // Middle East & Asia
        if (Pattern.compile("\\bMSK\\b").matcher(upper).find()) return ZoneOffset.of("+03:00");
        if (Pattern.compile("\\bGST\\b").matcher(upper).find()) return ZoneOffset.of("+04:00");
        if (Pattern.compile("\\bIST\\b").matcher(upper).find()) return ZoneOffset.of("+05:30");
        if (Pattern.compile("\\b(ICT|WIB)\\b").matcher(upper).find()) return ZoneOffset.of("+07:00");
        if (Pattern.compile("\\b(SGT|MYT|PHT|WITA|HKT)\\b").matcher(upper).find()) return ZoneOffset.of("+08:00");
        if (Pattern.compile("\\b(JST|KST)\\b").matcher(upper).find()) return ZoneOffset.of("+09:00");

        // Australia & New Zealand
        if (Pattern.compile("\\bACST\\b").matcher(upper).find()) return ZoneOffset.of("+09:30");
        if (Pattern.compile("\\bACDT\\b").matcher(upper).find()) return ZoneOffset.of("+10:30");
        if (Pattern.compile("\\bAEST\\b").matcher(upper).find()) return ZoneOffset.of("+10:00");
        if (Pattern.compile("\\bAEDT\\b").matcher(upper).find()) return ZoneOffset.of("+11:00");
        if (Pattern.compile("\\bNZST\\b").matcher(upper).find()) return ZoneOffset.of("+12:00");
        if (Pattern.compile("\\bNZDT\\b").matcher(upper).find()) return ZoneOffset.of("+13:00");

        // Americas
        if (Pattern.compile("\\bEDT\\b").matcher(upper).find()) return ZoneOffset.of("-04:00");
        if (Pattern.compile("\\bEST\\b").matcher(upper).find()) return ZoneOffset.of("-05:00");
        if (Pattern.compile("\\bCDT\\b").matcher(upper).find()) return ZoneOffset.of("-05:00");
        if (Pattern.compile("\\bCST\\b").matcher(upper).find()) return ZoneOffset.of("-06:00");
        if (Pattern.compile("\\bMDT\\b").matcher(upper).find()) return ZoneOffset.of("-06:00");
        if (Pattern.compile("\\bMST\\b").matcher(upper).find()) return ZoneOffset.of("-07:00");
        if (Pattern.compile("\\bPDT\\b").matcher(upper).find()) return ZoneOffset.of("-07:00");
        if (Pattern.compile("\\bPST\\b").matcher(upper).find()) return ZoneOffset.of("-08:00");
        if (Pattern.compile("\\bAKDT\\b").matcher(upper).find()) return ZoneOffset.of("-08:00");
        if (Pattern.compile("\\bAKST\\b").matcher(upper).find()) return ZoneOffset.of("-09:00");
        if (Pattern.compile("\\bHST\\b").matcher(upper).find()) return ZoneOffset.of("-10:00");
        if (Pattern.compile("\\bAST\\b").matcher(upper).find()) return ZoneOffset.of("-04:00");
        if (Pattern.compile("\\bBRT\\b").matcher(upper).find()) return ZoneOffset.of("-03:00");
        // Generic US zones follow daylight saving for the event's date (PDT/PST/EDT/... above stay fixed on purpose).
        if (Pattern.compile("\\bPT\\b").matcher(upper).find()) return ZoneId.of("America/Los_Angeles");
        if (Pattern.compile("\\bMT\\b").matcher(upper).find()) return ZoneId.of("America/Denver");
        if (Pattern.compile("\\bCT\\b").matcher(upper).find()) return ZoneId.of("America/Chicago");
        if (Pattern.compile("\\bET\\b").matcher(upper).find()) return ZoneId.of("America/New_York");

        return ZoneOffset.UTC;
    }

    public static long parseEventEpoch(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) return 0;
        String text = rawTime.trim();

        try {
            int year = 0, month = 0, day = 0;

            // 1. Check YYYY-MM-DD or YYYY-DD-MM (e.g. 2026-23-10 or 2026-10-23)
            Matcher ymdMatcher = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").matcher(text);
            if (ymdMatcher.find()) {
                year = Integer.parseInt(ymdMatcher.group(1));
                int p2 = Integer.parseInt(ymdMatcher.group(2));
                int p3 = Integer.parseInt(ymdMatcher.group(3));
                if (p2 > 12 && p3 <= 12) {
                    day = p2;
                    month = p3;
                } else {
                    month = p2;
                    day = p3;
                }
            } else {
                // 2. Check DD-MM-YYYY or MM-DD-YYYY
                Matcher dmyMatcher = Pattern.compile("(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})").matcher(text);
                if (dmyMatcher.find()) {
                    int p1 = Integer.parseInt(dmyMatcher.group(1));
                    int p2 = Integer.parseInt(dmyMatcher.group(2));
                    year = Integer.parseInt(dmyMatcher.group(3));
                    if (p1 > 12 && p2 <= 12) {
                        day = p1;
                        month = p2;
                    } else if (p2 > 12 && p1 <= 12) {
                        month = p1;
                        day = p2;
                    } else {
                        month = p1;
                        day = p2;
                    }
                } else {
                    // 3. Named month e.g. "October 24, 2026" or "24 October 2026"
                    Matcher namedMonth = Pattern.compile("(?i)(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})").matcher(text);
                    if (namedMonth.find()) {
                        month = parseMonthName(namedMonth.group(1));
                        day = Integer.parseInt(namedMonth.group(2));
                        year = Integer.parseInt(namedMonth.group(3));
                    } else {
                        Matcher namedMonthDayFirst = Pattern.compile("(?i)(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?),?\\s+(\\d{4})").matcher(text);
                        if (namedMonthDayFirst.find()) {
                            day = Integer.parseInt(namedMonthDayFirst.group(1));
                            month = parseMonthName(namedMonthDayFirst.group(2));
                            year = Integer.parseInt(namedMonthDayFirst.group(3));
                        }
                    }
                }
            }

            if (year < 2000 || month < 1 || month > 12 || day < 1 || day > 31) return 0;

            Matcher timeMatcher = Pattern.compile("(?i)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(text);
            if (!timeMatcher.find()) return 0;
            int hour = Integer.parseInt(timeMatcher.group(1));
            int min = Integer.parseInt(timeMatcher.group(2));
            String ampm = timeMatcher.group(4);
            if (ampm != null) {
                if (ampm.equalsIgnoreCase("pm") && hour < 12) hour += 12;
                else if (ampm.equalsIgnoreCase("am") && hour == 12) hour = 0;
            }

            ZoneId zoneId = parseEventZoneId(text);
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