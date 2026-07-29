package com.amore;

import java.awt.Color;
import net.dv8tion.jda.api.EmbedBuilder;
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

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals("menu_fused")) return;

        String selectedValue = event.getValues().get(0);

        String[] parts = selectedValue.split(":");
        String type = parts[0];
        String audience = parts[1];
        String urgency = parts[2];

        TextInput.Builder hostBuilder = TextInput.create("input_host", "Host / Trainer", TextInputStyle.SHORT)
                .setPlaceholder("e.g. @Deadcha or Name").setRequired(true);
        if (type.equals("training") || type.equals("training_comp")) hostBuilder.setLabel("Trainer");

        TextInput timeInput = TextInput.create("input_time", "Time", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 8:00 PM EST").setRequired(true).build();

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
        String targetPingChannelId;

        if (audience.equals("member")) {
            targetPingChannelId = System.getenv("MEMBER_PING_ID");
            if (urgency.equals("urgent")) {
                targetForumId = System.getenv("URGENT_BOUNTY_FORUM_ID");
            } else {
                targetForumId = System.getenv("STANDARD_BOUNTY_FORUM_ID");
            }
        } else {
            targetForumId = System.getenv("STANDARD_BOUNTY_FORUM_ID");
            targetPingChannelId = System.getenv("LOUNGE_CHANNEL_ID");
        }

        String host = event.getValue("input_host").getAsString();
        String time = event.getValue("input_time").getAsString();
        String slots = event.getValue("input_slots").getAsString();
        
        int tempReward = 0;
        try { tempReward = Integer.parseInt(event.getValue("input_reward").getAsString().trim()); } catch (Exception ignored) {}
        final int reward = tempReward;

        String extra = event.getValue("input_extra") != null ? event.getValue("input_extra").getAsString() : "";
        String aestheticHeader = buildTemplateHeader(type, host, time, extra, slots);

        int maxSlots = 0;
        try { maxSlots = Integer.parseInt(slots.trim()); } catch (Exception ignored) {}
        String slotDisplay = maxSlots <= 0 ? "Unlimited" : String.valueOf(maxSlots);

        String displayTitle = (audience.equals("member") ? (urgency.equals("urgent") ? "🚨 " : "👑 ") : "🌍 ") + type.replace("_", " ").toUpperCase();

        Color embedColor;
        if (urgency.equals("urgent")) {
            embedColor = new Color(220, 20, 60); // Crimson for urgent
        } else if (audience.equals("member")) {
            embedColor = new Color(255, 215, 0); // Gold for standard members
        } else {
            embedColor = new Color(255, 69, 0);  // Orange for everyone
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
            event.reply("⚠️ **Routing Error:** Missing Environment Variables! Make sure `STANDARD_BOUNTY_FORUM_ID`, `URGENT_BOUNTY_FORUM_ID`, `LOUNGE_CHANNEL_ID`, and `MEMBER_PING_ID` are set.").setEphemeral(true).queue();
            return;
        }

        ForumChannel targetForum = event.getJDA().getForumChannelById(targetForumId);
        
        if (targetForum != null) {
            event.deferReply(true).queue();
            
            targetForum.createForumPost(displayTitle, builder.build()).queue(
                forumPost -> {
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
                    
                    event.getHook().sendMessage("✅ Hybrid Event automatically routed to the correct Forum and Ping Channel!").queue();
                },
                error -> event.getHook().sendMessage("⚠️ Error creating forum post: " + error.getMessage()).queue()
            );
        } else {
            event.reply("⚠️ Routing Error: Could not find the target forum channel. Please check the ID.").setEphemeral(true).queue();
        }
    }

    private String buildTemplateHeader(String type, String host, String time, String extra, String members) {
        switch (type) {
            case "training":
                return "# ୧ ╰ 𝐀𝐌𝐎𝐑𝐀 𝐓𝐑𝐀𝐈𝐍𝐈𝐍𝐆 . .ᐟ\n" +
                       " _ ⌢ ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━ ⌢ _\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐫𝐚𝐢𝐧𝐞𝐫 :` \n\n" +
                       "` ~ ୨୧ ·  " + host + " `\n\n\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` \n\n" +
                       "` ~ ୨୧ ·  " + time + " `\n\n\n" +
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
                       "` ~ ୨୧ ·  " + time + " `\n\n\n" +
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
                       "` ~ ୨୧ ·  " + time + " `\n\n\n" +
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
                       "` ~ ୨୧ ·  " + time + " `\n\n\n" +
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
                       "` ~ ୨୧ ·  " + time + " `\n\n\n" +
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
                       "` ~ ୨୧ ·  " + time + " `\n\n\n" +
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
                       "` ~ ୨୧ ·  " + time + " `\n\n\n" +
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