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
        String token = requiredEnv("DISCORD_TOKEN").trim().replaceAll("^\"|\"$", "");

        System.out.println("✦ Starting AMORA Bot JDA Instance...");

        DatabaseManager db = DatabaseManager.getInstance();
        db.cleanupExpiredSessions();

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(net.dv8tion.jda.api.requests.GatewayIntent.MESSAGE_CONTENT)
                    .setActivity(Activity.playing("AMORA ✦: Matcha Says to Type /pull"))
                    .addEventListeners(new CommandListener(), new ChatListener(), new AnnouncementListener(), new MusicListener(), new GameSpotlight())
                    .build();

            jda.awaitReady();
            System.out.println("✦ AMORA Bot is officially ONLINE 24/7!");
            StatusRotator.start(jda);
            LoungeManager.start(jda);
            GameSpotlight.start(jda);
            RoleManager.start(jda);
            ActivityMonitor.start(jda);
            int webPort = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
            InternalApiServer.startServer(webPort, jda);
            
            
            jda.updateCommands()
                .addCommands(
                    Commands.slash("forcesweep", "Admin Only: Manually trigger task drops or activity sweeps for testing")
                        .addOption(OptionType.STRING, "type", "Choose what to test: 'tasks' or 'sweep'", true),
                    Commands.slash("testrelay", "Admin Only: Test relay connection to category halls")
                        .addOption(OptionType.STRING, "hall", "Which hall to test: 'outfit', 'build', 'lyrics', 'face', 'commission', or 'all'", false),
                    Commands.slash("syncschedule", "HR: Scan a manually posted schedule message and set up daily reminders")
                        .addOption(OptionType.STRING, "message_id", "The ID of the message containing the schedule", true),
                    Commands.slash("taskbank", "Director Only: Add a new task to the randomized monthly pool")
                        .addOption(OptionType.STRING, "rank", "Which rank? (Flare, Spark, Neon, Pulse, Prism, Supernova)", true),
                    Commands.slash("toggleapps", "HR Only: Open or close Official Member applications"),
                    Commands.slash("amorahub", "Admin: Spawn the interactive AMORA Member Dashboard"),
                    Commands.slash("help", "Displays a list of all available AMORA commands and how to use them!"),
                    Commands.slash("serverreport", "Director Only: Generate the AMORA Monthly Global Performance Report"), 
                    Commands.slash("rolepanel", "Admin: Spawn the aesthetic Welcome & Role Selection Panel"),
                    Commands.slash("agepanel", "Admin: Deploy the secure 18+ and Minor age selection panel"),
                    Commands.slash("verify", "Verify you are 18+ (zero-knowledge ID proof or self-attestation) to unlock the Adult Zone"),
                    Commands.slash("unverify", "Admin: Reset a user's verification status for testing")
                            .addOption(OptionType.USER, "target", "The user to unverify (defaults to yourself)", false),
                    Commands.slash("ageban", "Admin: Manage the 18+ verification ban list")
                            .addSubcommands(
                                new SubcommandData("add", "Ban a user from automatic 18+ verification (also removes their 18+ role)")
                                    .addOption(OptionType.USER, "target", "The user to ban", true)
                                    .addOption(OptionType.STRING, "reason", "Why (shown in the list and audit log)", false),
                                new SubcommandData("remove", "Lift a user's age-verification ban")
                                    .addOption(OptionType.USER, "target", "The user to unban", true),
                                new SubcommandData("list", "Show the latest age-verification bans")
                            ),
                    Commands.slash("gift", "Send a comforting treat or gift to M.IKU to lower her stress level!")
                        .addOptions(new OptionData(OptionType.STRING, "item", "Choose what to gift M.IKU", true)
                            .addChoice("🍵 Warm Matcha Latte (50 Sparks) [-1 Stress]", "matcha")
                            .addChoice("🧸 Leek Plushie (150 Sparks) [-3 Stress]", "plushie")
                            .addChoice("🧖‍♀️ Luxury Spa Voucher (300 Sparks) [Full Reset]", "spa")
                            .addChoice("⚡ Energy Drink & Snack (100 Sparks) [Hyper Mood]", "energy")
                        ),
                    Commands.slash("eventsetup", "Admin: Spawn the Smart UI Fused Event Panel"),
                    Commands.slash("pull", "Spend 50 Sparks to pull a random Gacha reward!"),
                    Commands.slash("balance", "Check your current AMORA Sparks balance."),
                    Commands.slash("inventory", "View your collected Gacha photocards and assets"),
                    Commands.slash("forge", "Open the Synthesis Forge to craft or recycle items"),
                    Commands.slash("transcript", "Director Only: Export a text transcript of the current order thread"),
                    Commands.slash("leaderboard", "View the Top 10 rankings in the server")
                        .addOptions(new OptionData(OptionType.STRING, "category", "Which leaderboard to view", true)
                            .addChoice("Wealth & Assets", "wealth")
                            .addChoice("Standard Directives", "bounties")
                            .addChoice("Urgent Directives", "urgent")
                            .addChoice("⏱ Active Check Wins", "acwins") 
                            .addChoice("🛒 Monthly Shop Orders", "orders_month")   
                            .addChoice("👑 All-Time Shop Orders", "orders_alltime")
                            .addChoice("🎵 Top Music Curators", "curators")
                        ),

                    Commands.slash("shop", "AMORA Creator Marketplace")
                        .addSubcommands(
                            new SubcommandData("evaluate", "Generates a report of sellers with 0 sales for manual check.")
                                .addOption(OptionType.BOOLEAN, "reset", "Reset all shop sales counters to 0 after generating?", true)
                        ),

                    Commands.slash("commissions", "Toggle whether you accept custom commissions in your shop listings")
                        .addOption(OptionType.BOOLEAN, "enabled", "True to accept commissions, False for ready-made sales only", false),

                    Commands.slash("commission", "Toggle whether you accept custom commissions in your shop listings")
                        .addOption(OptionType.BOOLEAN, "enabled", "True to accept commissions, False for ready-made sales only", false),

                    Commands.context(net.dv8tion.jda.api.interactions.commands.Command.Type.MESSAGE, "🛒 Order This"),

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

                    // Commands.slash("bounty", "Manage the Forum Quest Boards")
                    //     .addSubcommands(
                    //         new SubcommandData("post", "Director Only: Post a new quest to a Bounty Forum")
                    //             .addOption(OptionType.CHANNEL, "forum", "The Quest Forum (Standard or Urgent)", true)
                    //             .addOption(OptionType.STRING, "title", "The name of the quest", true)
                    //             .addOption(OptionType.INTEGER, "reward", "Points reward PER PERSON", true)
                    //             .addOption(OptionType.STRING, "slots", "Max players (e.g. 4, 'Unlimited', 'Not Necessary')", true)
                    //             .addOption(OptionType.STRING, "description", "Type \\n for a new line, or leave empty to use a .txt file!", false)
                    //             .addOption(OptionType.ATTACHMENT, "desc_file", "Upload a .txt notepad file for a detailed description", false)
                    //             .addOption(OptionType.ATTACHMENT, "image", "Optional banner image for the quest card", false),

                    //         new SubcommandData("kick", "Director Only: Remove a freeloader from the party list")
                    //             .addOption(OptionType.USER, "target", "The member to kick", true),

                    //         new SubcommandData("cancel", "Director Only: Abort and lock a quest without paying anyone"),

                    //         new SubcommandData("complete", "Director Only: Run INSIDE a quest thread to mass-pay the party & lock!")
                    //             .addOption(OptionType.STRING, "exclude", "Tag freeloaders to EXCLUDE from payout (e.g. @troll)", false)
                    //     ),
                        
                    Commands.slash("activitycheck", "Director Only: Configure the Activity Check template keywords & limits")
                        .addOption(OptionType.STRING, "trigger", "Required word/symbol in the message (e.g. ᯓ★)", true)
                        .addOption(OptionType.STRING, "react_phrase", "Phrase directly before the emoji (e.g. React with)", true)
                        .addOption(OptionType.STRING, "goal_phrase", "Phrase directly before the target number (e.g. Goal)", true)
                        .addOption(OptionType.INTEGER, "daily_limit", "Max number of Activity Checks allowed per day", false),


                    Commands.slash("profile", "View your AMORA profile")
                        .addOption(OptionType.USER, "user", "Whose profile to view", false)
                )
                .addCommands(MusicListener.commands())
                .addCommands(GameSpotlight.command())
                .queue();

            System.out.println("✦ Slash commands registered successfully.");
            MusicService.start(jda);

        } catch (Exception e) {
            System.out.println("❌ Critical failure during bot initialization.");
            e.printStackTrace();
        }
    }

}