package com.amore;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
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
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("menu_fused")) return;

        String selectedValue = event.getValues().get(0);
        String[] parts = selectedValue.split(":");
        String type = parts[0];
        String audience = parts[1];
        String urgency = parts[2];

        event.replyModal(buildEventModal("modal_fused:", "Create Hybrid Event", type, audience, urgency, "", "", "", "", "")).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
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
        
        String displayTime = rawTime;
        long unixEpoch = 0;
        try {
            String smartTime = rawTime.toUpperCase();
            smartTime = smartTime.replaceAll("(GMT|UTC)\\+(\\d)$", "$1+0$2:00");
            smartTime = smartTime.replaceAll("(GMT|UTC)\\-(\\d)$", "$1-0$2:00");
            smartTime = smartTime.replaceAll("(GMT|UTC)\\+(\\d{2})$", "$1+$2:00");
            smartTime = smartTime.replaceAll("(GMT|UTC)\\-(\\d{2})$", "$1-$2:00");

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
            ZonedDateTime zdt = ZonedDateTime.parse(smartTime, formatter);
            unixEpoch = zdt.toEpochSecond();
            displayTime = "<t:" + unixEpoch + ":F>\n` ~ ୨୧ · ` <t:" + unixEpoch + ":R>";
        } catch (Exception e) {
            displayTime = "` " + rawTime + " `"; 
        }
        
        int tempReward = 0;
        try { tempReward = Integer.parseInt(event.getValue("input_reward").getAsString().trim()); } catch (Exception ignored) {}
        final int reward = tempReward;

        String extra = event.getValue("input_extra") != null ? event.getValue("input_extra").getAsString() : "";
        String aestheticHeader = buildTemplateHeader(type, host, displayTime, extra, slots);

        int maxSlots = 0;
        try { maxSlots = Integer.parseInt(slots.trim()); } catch (Exception ignored) {}
        String slotDisplay = maxSlots <= 0 ? "Unlimited" : String.valueOf(maxSlots);

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

        EmbedBuilder questEmbed = new EmbedBuilder()
                .setColor(embedColor)
                .setTitle(displayTitle)
                .setDescription("💰 **Bounty Reward:** `" + reward + " Points` _(Per Person)_\n" +
                                (audience.equals("member") ? "\n⚠️ **Role Requirement:** Official Members Only!\n" : "") +
                                "\n_Click **Join Quest** below to claim your spot in the party!_")
                .addField("👥 Party [" + currentSlots + "/" + slotDisplay + "]", partyField, false)
                .setFooter("AMORA Event Directive • Reward embedded: " + reward + " • Time: " + rawTime, null);

        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent(aestheticHeader);
        builder.addEmbeds(questEmbed.build());
        
        builder.addActionRow(
                Button.success("qjoin_" + audience, "✋ Join Quest"),
                Button.danger("qleave_button", "⭕ Leave Quest"),
                Button.secondary("alert_party", "🔔 Alert Party"),
                Button.primary("edit_event:" + type + ":" + audience + ":" + urgency, "✏️ Edit (Staff)")
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
                            
                            String notificationMessage = aestheticHeader + 
                                "\n\n🔗 **>> [CLICK HERE TO RSVP ON THE QUEST BOARD](" + forumPost.getThreadChannel().getJumpUrl() + ") <<**\n\n" +
                                pingMention + " . 00 . > Amora < . <3.";

                            if (textChannel != null) {
                                textChannel.sendMessage(notificationMessage).queue();
                            } else {
                                newsChannel.sendMessage(notificationMessage).queue();
                            }
                            
                            event.getHook().sendMessage("✅ Hybrid Event successfully routed!").queue();
                        } else {
                            event.getHook().sendMessage("✅ Event created in Forum, but ⚠️ **could not find the target schedules channel!** Check permissions and ID.").queue();
                        }
                    },
                    error -> event.getHook().sendMessage("⚠️ Error creating forum post: " + error.getMessage()).queue()
                );
            } else {
                event.reply("⚠️ Routing Error: Could not find the target forum channel.").setEphemeral(true).queue();
            }
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();

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
            Matcher mExtra = Pattern.compile("(?i)(?:𝐒𝐞𝐫𝐯𝐞𝐫|𝐓𝐡𝐞𝐦𝐞|𝐆𝐚𝐦𝐞𝐬)\\s*:`?\\s*\\n+\\s*`\\s*~\\s*୨୧\\s*·\\s*(.*?)\\s*`").matcher(content);
            if (mExtra.find()) extra = mExtra.group(1).trim();

            String slots = "";
            Matcher mSlots = Pattern.compile("(?i)𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 𝐨𝐟 𝐌𝐞𝐦𝐛𝐞𝐫𝐬 𝐍𝐞𝐞𝐝𝐞𝐝\\s*:`?\\s*\\n+\\s*`\\s*~\\s*୨୧\\s*·\\s*(.*?)\\s*`").matcher(content);
            if (mSlots.find()) slots = mSlots.group(1).trim();

            String rewardStr = "0";
            String timeStr = "";
            if (embed != null && embed.getFooter() != null && embed.getFooter().getText() != null) {
                String footerText = embed.getFooter().getText();
                Matcher mReward = Pattern.compile("Reward embedded:\\s*(\\d+)").matcher(footerText);
                if (mReward.find()) rewardStr = mReward.group(1);
                
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

            event.replyModal(buildEventModal("modal_edit:", "Edit Hybrid Event", type, audience, urgency, host, timeStr, extra, slots, rewardStr)).queue();
            return;
        }

        if (buttonId.startsWith("qjoin_") || buttonId.equals("qleave_button") || buttonId.equals("alert_party")) {
            
            MessageEmbed embed = event.getMessage().getEmbeds().get(0);
            String partyField = "";
            String slotDisplay = "Unlimited";
            int maxSlots = 0;
            int currentSlots = 0;
            int fieldIndex = -1;

            for (int i = 0; i < embed.getFields().size(); i++) {
                MessageEmbed.Field field = embed.getFields().get(i);
                if (field.getName() != null && field.getName().startsWith("👥 Party")) {
                    partyField = field.getValue();
                    fieldIndex = i;
                    
                    Matcher m = Pattern.compile("\\[(\\d+)/([\\dUnlimited]+)\\]").matcher(field.getName());
                    if (m.find()) {
                        currentSlots = Integer.parseInt(m.group(1));
                        slotDisplay = m.group(2);
                        if (!slotDisplay.equals("Unlimited")) maxSlots = Integer.parseInt(slotDisplay);
                    }
                    break;
                }
            }

            if (fieldIndex == -1) {
                event.reply("⚠️ Error: Could not read party data.").setEphemeral(true).queue();
                return;
            }

            String userId = event.getUser().getId();
            String userMention = "<@" + userId + ">";

            if (buttonId.equals("alert_party")) {
                if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.MESSAGE_MANAGE)) {
                    event.reply("⚠️ **Access Denied:** Only Staff can manually alert the party!").setEphemeral(true).queue();
                    return;
                }
                if (partyField.equals("None") || partyField.isEmpty()) {
                    event.reply("❌ The party is currently empty!").setEphemeral(true).queue();
                    return;
                }
                
                StringBuilder pings = new StringBuilder("🔔 **MANUAL STAFF ALERT:**\n");
                Matcher m = Pattern.compile("<@!?(\\d+)>").matcher(partyField);
                while (m.find()) pings.append("<@").append(m.group(1)).append("> ");
                
                event.getMessage().getChannel().sendMessage(pings.toString()).queue();
                event.reply("✅ The party has been successfully alerted!").setEphemeral(true).queue();
                return;
            }

            if (buttonId.startsWith("qjoin_")) {
                if (buttonId.equals("qjoin_member")) {
                    String memberRoleId = System.getenv("MEMBER_ROLE_ID");
                    if (memberRoleId != null && !memberRoleId.isBlank()) {
                        if (event.getMember().getRoles().stream().noneMatch(r -> r.getId().equals(memberRoleId))) {
                            event.reply("⚠️ **Access Denied:** This event is strictly for Official Members!").setEphemeral(true).queue();
                            return;
                        }
                    }
                }

                if (partyField.contains(userId)) {
                    event.reply("❌ You are already in the party!").setEphemeral(true).queue();
                    return;
                }

                if (maxSlots > 0 && currentSlots >= maxSlots) {
                    event.reply("⚠️ **Party Full:** There are no slots remaining for this event.").setEphemeral(true).queue();
                    return;
                }

                partyField = partyField.equals("None") ? userMention : partyField + "\n" + userMention;
                currentSlots++;
                
            } else if (buttonId.equals("qleave_button")) {
                if (!partyField.contains(userId)) {
                    event.reply("❌ You aren't in the party!").setEphemeral(true).queue();
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
            newEmbed.getFields().set(fieldIndex, new MessageEmbed.Field("👥 Party [" + currentSlots + "/" + slotDisplay + "]", partyField, false));
            
            event.editMessageEmbeds(newEmbed.build())
                 .setComponents(event.getMessage().getComponents()) 
                 .queue();
        }
    }

    private Modal buildEventModal(String idPrefix, String title, String type, String audience, String urgency, 
                                  String defaultHost, String defaultTime, String defaultExtra, String defaultSlots, String defaultReward) {
        
        TextInput.Builder hostBuilder = TextInput.create("input_host", "Host / Trainer", TextInputStyle.SHORT)
                .setPlaceholder("e.g. @Deadcha or Name").setRequired(true);
        if (type.equals("training") || type.equals("training_comp")) hostBuilder.setLabel("Trainer");
        if (!defaultHost.isBlank()) hostBuilder.setValue(defaultHost);

        TextInput.Builder timeBuilder = TextInput.create("input_time", "Time (yyyy-MM-dd HH:mm Timezone)", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 2026-07-31 20:00 GMT+7").setRequired(true);
        if (!defaultTime.isBlank()) timeBuilder.setValue(defaultTime);

        TextInput.Builder slotsBuilder = TextInput.create("input_slots", "Party Slots / Min Members", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 5 or 0 for Unlimited").setRequired(true);
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
                       "`~ ୨୧ ·  𝐒𝐞𝐫𝐯𝐞𝐫 :` \n\n" +
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

            default: return "Error building template.";
        }
    }
}