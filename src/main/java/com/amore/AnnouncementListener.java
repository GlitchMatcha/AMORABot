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
        if (!event.getComponentId().startsWith("menu_fused_")) return;

        String[] menuParts = event.getComponentId().split("_");
        String targetForumId = menuParts[2];
        String targetPingChannelId = menuParts[3];

        String selectedValue = event.getValues().get(0);

        int lastUnderscore = selectedValue.lastIndexOf('_');
        String type = selectedValue.substring(0, lastUnderscore);
        String audience = selectedValue.substring(lastUnderscore + 1);

        TextInput.Builder hostBuilder = TextInput.create("input_host", "Host / Trainer", TextInputStyle.SHORT)
                .setPlaceholder("e.g. @Deadcha or Name").setRequired(true);
        if (type.equals("training") || type.equals("training_comp")) hostBuilder.setLabel("Trainer");

        TextInput timeInput = TextInput.create("input_time", "Time", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 8:00 PM EST").setRequired(true).build();

        TextInput slotsInput = TextInput.create("input_slots", "Party Slots / Min Members", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 5 or 0 for Unlimited").setRequired(true).build();

        TextInput rewardInput = TextInput.create("input_reward", "Reward (Points per person)", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 50").setRequired(true).build();

        Modal.Builder modal = Modal.create("modal_fused_" + targetForumId + "_" + targetPingChannelId + "_" + type + "_" + audience, "Create Hybrid Event");
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
        if (!event.getModalId().startsWith("modal_fused_")) return;

        String[] parts = event.getModalId().split("_");
        String targetForumId = parts[2];
        String targetPingChannelId = parts[3];
        String type = parts[4];
        String audience = parts[5]; 

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

        String displayTitle = (audience.equals("member") ? "👑 " : "🌍 ") + type.replace("_", " ").toUpperCase();

        EmbedBuilder questEmbed = new EmbedBuilder()
                .setColor(audience.equals("member") ? new Color(255, 215, 0) : new Color(255, 69, 0))
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

        ForumChannel targetForum = event.getJDA().getForumChannelById(targetForumId);
        
        if (targetForum != null) {
            event.deferReply(true).queue();
            
            targetForum.createForumPost(displayTitle, builder.build()).queue(
                forumPost -> {
                    TextChannel pingChannel = event.getJDA().getTextChannelById(targetPingChannelId);
                    if (pingChannel != null) {
                        String pingAudience = audience.equals("member") ? "Official Members" : "Everyone";
                        
                        EmbedBuilder pingEmbed = new EmbedBuilder()
                            .setColor(new Color(138, 43, 226))
                            .setTitle("📢 NEW EVENT POSTED: " + displayTitle)
                            .setDescription("A new AMORA Event has just been deployed to the quest board!\n\n" +
                                            "🎭 **Event Type:** " + type.replace("_", " ").toUpperCase() + "\n" +
                                            "👥 **Open To:** " + pingAudience + "\n" +
                                            "💰 **Reward:** `" + reward + " Points`\n\n" +
                                            "🔗 **[Click here to RSVP and secure your slot!](" + forumPost.getThreadChannel().getJumpUrl() + ")**");

                        pingChannel.sendMessage("@everyone").addEmbeds(pingEmbed.build()).queue();
                    }
                    
                    event.getHook().sendMessage("✅ Hybrid Event created in the forum and successfully cross-posted to the ping channel!").queue();
                },
                error -> event.getHook().sendMessage("⚠️ Error creating forum post: " + error.getMessage()).queue()
            );
        } else {
            event.reply("⚠️ Error: Target forum channel not found.").setEphemeral(true).queue();
        }
    }

    private String buildTemplateHeader(String type, String host, String time, String extra, String members) {
        switch (type) {
            case "training":
                return "#  ׄ ੭୧  ׄ ৻ 𝙰𝙼𝙾𝚁𝙰 𝚃𝚁𝙰𝙸𝙽𝙸𝙽𝙶  . .ᐟ\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n" +
                       "`~ ୨୧ ·  𝐓𝐫𝐚𝐢𝐧𝐞𝐫 :` `" + host + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` `" + time + "`\n" +
                       "` ~ ୨୧ · 𝐒𝐞𝐫𝐯𝐞𝐫 :` `" + extra + "`\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 :` `" + members + " Members`\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n";

            case "movie":
                return "#  ׄ ੭୧  ׄ ৻ 𝙼𝙾𝚅𝙸𝙴 𝙽𝙸𝙶𝙷𝚃 . .ᐟ\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` `" + host + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` `" + time + "`\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 :` `" + members + "`\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n";

            case "game":
                return "#   ׄ ੭୧  ׄ ৻ 𝙶𝙰𝙼𝙴 𝙽𝙸𝙶𝙷𝚃. .ᐟ\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` `" + host + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` `" + time + "`\n" +
                       "`~ ୨୧ ·  𝙶𝚊𝚖𝚎𝚜 :` `" + extra + "`\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 :` `" + members + "`\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n";

            case "photo":
                return "#   ׄ ੭୧  ׄ ৻ 𝙿𝙷𝙾𝚃𝙾𝚂𝙷𝙾𝙾𝚃. .ᐟ\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` `" + host + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` `" + time + "`\n" +
                       "`~ ୨୧ ·  𝐒𝐞𝐫𝐯𝐞𝐫 :` `" + extra + "`\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 :` `" + members + "`\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n";

            case "mini_comp":
                return "#   ׄ ੭୧  ׄ ৻ 𝙼𝙸𝙽𝙸 𝙲𝙾𝙼𝙿𝙴𝚃𝙸𝚃𝙸𝙾𝙽. .ᐟ\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` `" + host + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` `" + time + "`\n" +
                       "`~ ୨୧ ·  𝐒𝐞𝐫𝐯𝐞𝐫 :` `" + extra + "`\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 :` `" + members + "`\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n";

            case "fashion":
                return "#   ׄ ੭୧  ׄ ৻ 𝙵𝙰𝚂𝙷𝙸𝙾𝙽 𝚂𝙷𝙾𝚆. .ᐟ\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n" +
                       "`~ ୨୧ ·  𝐇𝐨𝐬𝐭 :` `" + host + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` `" + time + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐡𝐞𝐦𝐞 :` `" + extra + "`\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 :` `" + members + "`\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n";

            case "training_comp":
                return "#   ׄ ੭୧  ׄ ৻ 𝚃𝚁𝙰𝙸𝙽𝙸𝙽𝙶 𝙲𝙾𝙼𝙿. .ᐟ\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n" +
                       "`~ ୨୧ ·  𝐓𝐫𝐚𝐢𝐧𝐞𝐫 :` `" + host + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐢𝐦𝐞 :` `" + time + "`\n" +
                       "`~ ୨୧ ·  𝐓𝐡𝐞𝐦𝐞 :` `" + extra + "`\n" +
                       "`~ ୨୧ : 𝐌𝐢𝐧𝐢𝐦𝐮𝐦 𝐀𝐦𝐨𝐮𝐧𝐭 :` `" + members + "`\n" +
                       "   𓂃  ━━━━━━━━━━⊱♡⊰━━━━━━━━━━━   𓂃\n";

            default: return "Error building template.";
        }
    }
}