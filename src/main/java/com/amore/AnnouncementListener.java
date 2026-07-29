package com.amore;

import java.awt.Color;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

public class AnnouncementListener extends ListenerAdapter {

    private static final ScheduledExecutorService EVENT_SCHEDULER = Executors.newScheduledThreadPool(5);

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("menu_fused")) return;

        String selectedValue = event.getValues().get(0);

        String[] parts = selectedValue.split(":");
        String type = parts[0];
        String audience = parts[1];
        String urgency = parts[2];

        TextInput.Builder hostBuilder = TextInput.create("input_host", "Host / Trainer", TextInputStyle.SHORT)
                .setPlaceholder("e.g. @Deadcha or Name").setRequired(true);
        if (type.equals("training") || type.equals("training_comp")) hostBuilder.setLabel("Trainer");

        TextInput timeInput = TextInput.create("input_time", "Time (yyyy-MM-dd HH:mm timezone)", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 2026-07-31 20:00 EST").setRequired(true).build();

        TextInput slotsInput = TextInput.create("input_slots", "Party Slots / Min Members", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 5 or 0 for Unlimited").setRequired(true).build();

        TextInput rewardInput = TextInput.create("input_reward", "Reward (Points per person)", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 50").setRequired(true).build();

        Modal.Builder modal = Modal.create("modal_fused:" + type + ":" + audience + ":" + urgency, "Create Hybrid Event");
        modal.addActionRow(hostBuilder.build());
        modal.addActionRow(timeInput);

        if (type.equals("game")) {
            modal.addActionRow(TextInput.create("input_extra", "Games", TextInputStyle.SHORT).setRequired(true).build());
        } else if (type.equals("fashion") || type.equals("training_comp")) {
            modal.addActionRow(TextInput.create("input_extra", "Theme", TextInputStyle.SHORT).setRequired(true).build());
        } else if (!type.equals("movie")) {
            modal.addActionRow(TextInput.create("input_extra", "Server", TextInputStyle.SHORT).setRequired(true).build());
        }

        modal.addActionRow(slotsInput);
        modal.addActionRow(rewardInput);

        event.replyModal(modal.build()).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("modal_fused:")) return;

        String payload = event.getModalId().replace("modal_fused:", "");
        String[] parts = payload.split(":");
        String type = parts[0];
        String audience = parts[1];
        String urgency = parts[2];

        String targetForumId;
        String targetPingChannelId = System.getenv("SCHEDULE_CHANNEL_ID");

        if (audience.equals("member")) {
            if (urgency.equals("urgent")) {
                targetForumId = System.getenv("URGENT_BOUNTY_FORUM_ID");
            } else {
                targetForumId = System.getenv("STANDARD_BOUNTY_FORUM_ID");
            }
        } else {
            targetForumId = System.getenv("STANDARD_BOUNTY_FORUM_ID");
        }

        String host = event.getValue("input_host").getAsString();
        String slots = event.getValue("input_slots").getAsString();
        String rawTime = event.getValue("input_time").getAsString().trim();
        
        String displayTime = rawTime;
        long unixEpoch = 0;
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm timezone");
            ZonedDateTime zdt = ZonedDateTime.parse(rawTime, formatter);
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

        String displayTitle = (audience.equals("member") ? (urgency.equals("urgent") ? "🚨 " : "👑 ") : "🌍 ") + type.replace("_", " ").toUpperCase();

        Color embedColor;
        if (urgency.equals("urgent")) {
            embedColor = new Color(220, 20, 60); 
        } else if (audience.equals("member")) {
            embedColor = new Color(255, 215, 0); 
        } else {
            embedColor = new Color(255, 69, 0);  
        }

        EmbedBuilder questEmbed = new EmbedBuilder()
                .setColor(embedColor)
                .setTitle(displayTitle)
                .setDescription("💰 **Bounty Reward:** `" + reward + " Points` _(Per Person)_\n" +
                                (audience.equals("member") ? "\n⚠️ **Role Requirement:** Official Members Only!\n" : "") +
                                "\n_Click **Join Quest** below to claim your spot in the party!_")
                .addField("👥 Party [0/" + slotDisplay + "]", "None", false)
                .setFooter("AMORA Event Directive • Reward embedded: " + reward, null);

        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent(aestheticHeader);
        builder.addEmbeds(questEmbed.build());
        builder.addActionRow(
                Button.success("qjoin_" + audience, "✋ Join Quest"),
                Button.danger("bleave_button", "🛑 Leave"),
                Button.secondary("alert_party", "🔔 Alert Party (Staff)")
        );

        if (targetForumId == null || targetPingChannelId == null) {
            event.reply("⚠️ **Routing Error:** Missing Environment Variables! Make sure `STANDARD_BOUNTY_FORUM_ID`, `URGENT_BOUNTY_FORUM_ID`, and `SCHEDULE_CHANNEL_ID` are set.").setEphemeral(true).queue();
            return;
        }

        ForumChannel targetForum = event.getJDA().getForumChannelById(targetForumId);
        
        if (targetForum != null) {
            event.deferReply(true).queue();
            
            final long finalUnixEpoch = unixEpoch;
            
            targetForum.createForumPost(displayTitle, builder.build()).queue(
                forumPost -> {
                    
                    if (finalUnixEpoch > 0) {
                        long currentTime = System.currentTimeMillis() / 1000;
                        long secondsUntil30Mins = finalUnixEpoch - currentTime - (30 * 60); 
                        long secondsUntilStart = finalUnixEpoch - currentTime; 
                        
                        if (secondsUntil30Mins > 0) {
                            EVENT_SCHEDULER.schedule(() -> {
                                String dmMsg = "# ୧ ╰ 𝐀𝐌𝐎𝐑𝐀 𝐄𝐕𝐄𝐍𝐓 𝐑𝐄𝐌𝐈𝐍𝐃𝐄𝐑 . .ᐟ\n" +
                                               " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n" +
                                               "`~ ୨୧ · ` 𝐇𝐢 **%s**!\n" +
                                               "`~ ୨୧ · ` 𝐓𝐡𝐞 𝐞𝐯𝐞𝐧𝐭 𝐲𝐨𝐮 𝐑𝐒𝐕𝐏'𝐝 𝐭𝐨 𝐢𝐬 𝐬𝐭𝐚𝐫𝐭𝐢𝐧𝐠 𝐢𝐧 **𝟑𝟎 𝐌𝐢𝐧𝐮𝐭𝐞𝐬**!\n" +
                                               "`~ ୨୧ · ` 𝐏𝐥𝐞𝐚𝐬𝐞 𝐬𝐭𝐚𝐫𝐭 𝐠𝐞𝐭𝐭𝐢𝐧𝐠 𝐫𝐞𝐚𝐝𝐲.. ⑅<:SCfeltcutemightdeletelateridk:1526912666835357736>\n\n" +
                                               "🔗 **>> [CLICK HERE TO JUMP TO THE EVENT](" + forumPost.getThreadChannel().getJumpUrl() + ") <<**";
                                String threadMsg = "🔔 **AUTOMATED REMINDER:** The event is starting in 30 minutes! Warning DMs have been dispatched to the party.";
                                sendPartyReminders(forumPost.getThreadChannel(), event.getJDA(), dmMsg, threadMsg);
                            }, secondsUntil30Mins, TimeUnit.SECONDS);
                        }

                        if (secondsUntilStart > 0) {
                            EVENT_SCHEDULER.schedule(() -> {
                                String dmMsg = "# ୧ ╰ 𝐀𝐌𝐎𝐑𝐀 𝐄𝐕𝐄𝐍𝐓 𝐒𝐓𝐀𝐑𝐓𝐈𝐍𝐆 . .ᐟ\n" +
                                               " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n" +
                                               "`~ ୨୧ · ` 𝐇𝐢 **%s**!\n" +
                                               "`~ ୨୧ · ` 𝐓𝐡𝐞 𝐞𝐯𝐞𝐧𝐭 𝐲𝐨𝐮 𝐑𝐒𝐕𝐏'𝐝 𝐭𝐨 𝐢𝐬 𝐬𝐭𝐚𝐫𝐭𝐢𝐧𝐠 **𝐑𝐈𝐆𝐇𝐓 𝐍𝐎𝐖**!\n" +
                                               "`~ ୨୧ · ` 𝐏𝐥𝐞𝐚𝐬𝐞 𝐡𝐞𝐚𝐝 𝐭𝐨 𝐭𝐡𝐞 𝐬𝐞𝐫𝐯𝐞𝐫 𝐢𝐦𝐦𝐞𝐝𝐢𝐚𝐭𝐞𝐥𝐲.. ⑅<a:animehype:1514915354894405702>\n\n" +
                                               "🔗 **>> [CLICK HERE TO JUMP TO THE EVENT](" + forumPost.getThreadChannel().getJumpUrl() + ") <<**";
                                String threadMsg = "🚨 **EVENT STARTING NOW:** The event has officially begun! Final DMs have been dispatched to the party.";
                                sendPartyReminders(forumPost.getThreadChannel(), event.getJDA(), dmMsg, threadMsg);
                            }, secondsUntilStart, TimeUnit.SECONDS);
                        }
                    }

                    TextChannel pingChannel = event.getJDA().getTextChannelById(targetPingChannelId);
                    if (pingChannel != null) {
                        
                        String memberRoleId = System.getenv("MEMBER_ROLE_ID");
                        String pingMention;
                        
                        if (audience.equals("member")) {
                            pingMention = (memberRoleId != null && !memberRoleId.isBlank()) ? "<@&" + memberRoleId + ">" : "**[Members Only]**";
                        } else {
                            pingMention = "@everyone";
                        }
                        
                        String notificationMessage = aestheticHeader + 
                            "\n\n🔗 **>> [CLICK HERE TO RSVP ON THE QUEST BOARD](" + forumPost.getThreadChannel().getJumpUrl() + ") <<**\n\n" +
                            pingMention + " . 00 . > Amora < . <3.";

                        pingChannel.sendMessage(notificationMessage).queue();
                    }
                    
                    event.getHook().sendMessage("✅ Hybrid Event routed! (Automated DMs will fire 30 mins before and exactly at start time).").queue();
                },
                error -> event.getHook().sendMessage("⚠️ Error creating forum post: " + error.getMessage()).queue()
            );
        } else {
            event.reply("⚠️ Routing Error: Could not find the target forum channel. Please check the ID.").setEphemeral(true).queue();
        }
    }

    private void sendPartyReminders(net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel threadChannel, net.dv8tion.jda.api.JDA jda, String dmTemplate, String threadAnnouncement) {
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
                        String personalizedMsg = dmTemplate.replace("%s", user.getName());
                        pc.sendMessage(personalizedMsg).queue(s->{}, e->{});
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