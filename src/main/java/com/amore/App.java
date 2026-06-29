package com.amore;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public class App {

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }

    public static void main(String[] args) {
        String token = requiredEnv("DISCORD_TOKEN");

        System.out.println("✦ Starting AMORA Bot JDA Instance...");

        DatabaseManager.getInstance();

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .setActivity(Activity.playing("AMORA ✦: Matcha Says to Type /pull"))
                    .addEventListeners(new CommandListener(), new ChatListener())
                    .build();

            jda.awaitReady();
            System.out.println("✦ AMORA Bot is officially ONLINE 24/7!");

            jda.updateCommands()
                .addCommands(
                    Commands.slash("pull", "Spend 50 Sparks to pull a random Gacha reward!"),
                    Commands.slash("balance", "Check your current AMORA Sparks balance."),
                    Commands.slash("inventory", "View your collected Gacha photocards and assets"),
                    Commands.slash("forge", "Open the Synthesis Forge to craft or recycle items"),

                    Commands.slash("leaderboard", "View the Top 10 rankings in the server")
                        .addOptions(new OptionData(OptionType.STRING, "category", "Which leaderboard to view", true)
                            .addChoice("💰 Wealth & Assets", "wealth")
                            .addChoice("🎯 Standard Directives", "bounties")
                            .addChoice("🚨 Urgent Directives", "urgent")
                        ),

                    Commands.slash("addsparks", "Admin: Add sparks to a user")
                        .addOption(OptionType.USER, "target", "The user receiving the Sparks", true)
                        .addOption(OptionType.INTEGER, "amount", "Amount of Sparks to give", true),

                    Commands.slash("award", "Admin: Publicly award Sparks for a competition or performance")
                        .addOption(OptionType.USER, "target", "The star performer", true)
                        .addOption(OptionType.INTEGER, "amount", "Sparks to award", true)
                        .addOption(OptionType.STRING, "reason", "Reason for the award", true),

                    Commands.slash("trade", "Open the trading window with another member")
                        .addOption(OptionType.USER, "target", "The member to trade with", true),

                    Commands.slash("payout", "Director Only: Award Points for completed bounties")
                        .addOption(OptionType.USER, "target", "The member receiving the payout", true)
                        .addOption(OptionType.INTEGER, "amount", "The amount of Points to award", true)
                        .addOption(OptionType.STRING, "reason", "The completed bounty or task", true),

                    Commands.slash("publish", "Director Only: Publish an item to the Forum Shop")
                        .addOption(OptionType.CHANNEL, "forum", "The Forum channel to post in", true)
                        .addOption(OptionType.STRING, "name", "Name of the asset", true)
                        .addOption(OptionType.INTEGER, "price", "Cost in Points", true)
                        .addOption(OptionType.STRING, "delivery", "The secret Link/Code to DM to the buyer", true)
                        .addOption(OptionType.STRING, "description", "Type \\n for a new line, or leave empty to use a .txt file!", false)
                        .addOption(OptionType.ATTACHMENT, "desc_file", "Upload a .txt notepad file for a detailed description", false)
                        .addOption(OptionType.ATTACHMENT, "file1", "Upload an image/GIF directly from your PC", false)
                        .addOption(OptionType.ATTACHMENT, "file2", "Upload a 2nd image/GIF directly", false)
                        .addOption(OptionType.STRING, "url1", "Or paste a web link to an image/GIF", false)
                        .addOption(OptionType.STRING, "url2", "Or paste a 2nd web link", false),

                    Commands.slash("bounty", "Manage the Forum Quest Boards")
                        .addSubcommands(
                            new SubcommandData("post", "Director Only: Post a new quest to a Bounty Forum")
                                .addOption(OptionType.CHANNEL, "forum", "The Quest Forum (Standard or Urgent)", true)
                                .addOption(OptionType.STRING, "title", "The name of the quest", true)
                                .addOption(OptionType.INTEGER, "reward", "Points reward PER PERSON", true)
                                .addOption(OptionType.STRING, "slots", "Max players (e.g. 4, 'Unlimited', 'Not Necessary')", true)
                                .addOption(OptionType.STRING, "description", "Type \\n for a new line, or leave empty to use a .txt file!", false)
                                .addOption(OptionType.ATTACHMENT, "desc_file", "Upload a .txt notepad file for a detailed description", false)
                                .addOption(OptionType.ATTACHMENT, "image", "Optional banner image for the quest card", false),

                            new SubcommandData("kick", "Director Only: Remove a freeloader from the party list")
                                .addOption(OptionType.USER, "target", "The member to kick", true),

                            new SubcommandData("cancel", "Director Only: Abort and lock a quest without paying anyone"),

                            new SubcommandData("complete", "Director Only: Run INSIDE a quest thread to mass-pay the party & lock!")
                                .addOption(OptionType.STRING, "exclude", "Tag freeloaders to EXCLUDE from payout (e.g. @troll)", false)
                        ),

                    Commands.slash("profile", "View your AMORA profile")
                        .addOption(OptionType.USER, "user", "Whose profile to view", false)
                ).queue();

            System.out.println("✦ Slash commands registered successfully.");

        } catch (Exception e) {
            System.out.println("❌ Critical failure during bot initialization.");
            e.printStackTrace();
        }
    }
}