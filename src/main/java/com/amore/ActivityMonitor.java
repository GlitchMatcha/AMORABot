package com.amore;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ActivityMonitor {

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    private static final String HIATUS_ROLE_ID = System.getenv("HIATUS_ROLE_ID"); 
    // 🚨 ADD THIS TO YOUR RENDER ENV VARIABLES:
    private static final String TASK_CHANNEL_ID = System.getenv("TASK_CHANNEL_ID"); 

    public static void start(JDA jda) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
                DatabaseManager db = DatabaseManager.getInstance();
                
                TextChannel taskChannel = TASK_CHANNEL_ID != null ? jda.getTextChannelById(TASK_CHANNEL_ID) : null;
                List<String> officialRanks = Arrays.asList("Flare", "Spark", "Neon", "Pulse", "Prism", "Supernova");

                if (now.getDayOfMonth() == 1) {
                    
                    String currentTaskKey = now.getYear() + "-" + now.getMonthValue() + "-TASKS";
                    if (!currentTaskKey.equals(db.getBotState("last_task_drop"))) {
                        db.setBotState("last_task_drop", currentTaskKey);
                        
                        if (taskChannel != null) {
                            taskChannel.sendMessage("# 📢 ✦ AMORA MONTHLY TASKS HAVE ARRIVED ✦\n" +
                                    "A new month means new challenges! The AMORA system has randomly selected this month's official tasks from the database. Please check your rank's assignment below!").queue();
                            
                            for (String rank : officialRanks) {
                                String randomTask = db.getRandomTaskForRank(rank);
                                if (randomTask != null) {
                                    taskChannel.sendMessage(randomTask).queue();
                                }
                            }
                        }
                    }

                    String currentSweepKey = now.getYear() + "-" + now.getMonthValue() + "-SWEEP";
                    if (!currentSweepKey.equals(db.getBotState("last_activity_sweep"))) {
                        System.out.println("✦ Starting Monthly AMORA Activity Sweep...");
                        db.setBotState("last_activity_sweep", currentSweepKey);

                        for (Guild guild : jda.getGuilds()) {
                            Role hiatusRole = HIATUS_ROLE_ID != null ? guild.getRoleById(HIATUS_ROLE_ID) : null;

                            guild.loadMembers().onSuccess(members -> {
                                for (Member member : members) {
                                    if (member.getUser().isBot()) continue;

                                    boolean isOfficial = member.getRoles().stream()
                                            .anyMatch(r -> officialRanks.contains(r.getName()));

                                    if (!isOfficial) continue;

                                    if (hiatusRole != null && member.getRoles().contains(hiatusRole)) {
                                        System.out.println("Skipping " + member.getEffectiveName() + " (Protected by Hiatus Bot)");
                                        continue; 
                                    }

                                    long lastTask = db.getLastTaskDate(member.getId());
                                    long thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);

                                    if (lastTask < thirtyDaysAgo) {
                                        int strikes = db.getActivityStrikes(member.getId()) + 1;
                                        db.setActivityStrikes(member.getId(), strikes);

                                        member.getUser().openPrivateChannel().queue(pc -> {
                                            if (strikes == 1) {
                                                pc.sendMessage("⚠️ **AMORA ACTIVITY WARNING:** You missed your task quota this month. Please ensure you attend and pass a task this month, or you will face Rank Decay! (Strike 1/3)").queue();
                                            } else if (strikes == 2) {
                                                pc.sendMessage("🚨 **AMORA RANK DECAY:** You have failed your activity quota for two consecutive months. You have been officially flagged for Rank Demotion by the Directors. (Strike 2/3)").queue();
                                            } else if (strikes >= 3) {
                                                pc.sendMessage("🛑 **AMORA REMOVAL:** You have been inactive for 3 months without an official Hiatus. Your official rank and Coin balance have been suspended. Please contact HR if you wish to return.").queue();
                                            }
                                        });
                                    } else {
                                        if (db.getActivityStrikes(member.getId()) > 0) {
                                            db.setActivityStrikes(member.getId(), 0);
                                            member.getUser().openPrivateChannel().queue(pc -> 
                                                pc.sendMessage("✅ **Activity Restored!** Thank you for completing tasks this month. Your activity strikes have been wiped clean!").queue()
                                            );
                                        }
                                    }
                                }
                            });
                        }
                    }
                }

                if (now.getDayOfMonth() == 15) {
                    String reminderKey = now.getYear() + "-" + now.getMonthValue() + "-REMINDER1";
                    if (!reminderKey.equals(db.getBotState("last_reminder_1"))) {
                        db.setBotState("last_reminder_1", reminderKey);
                        if (taskChannel != null) {
                            taskChannel.sendMessage(" **MID-MONTH REMINDER:** We are halfway through the month! If you haven't started your rank task yet, now is the time to gather your groups and start practicing!").queue();
                        }
                    }
                }

                if (now.getDayOfMonth() == 25) {
                    String reminderKey = now.getYear() + "-" + now.getMonthValue() + "-REMINDER2";
                    if (!reminderKey.equals(db.getBotState("last_reminder_2"))) {
                        db.setBotState("last_reminder_2", reminderKey);
                        if (taskChannel != null) {
                            taskChannel.sendMessage("🚨 **FINAL WARNING:** There are only a few days left to submit your monthly rank tasks! Remember, failing to submit your task will result in an activity strike and potential rank decay. Please reach out to your Rank Leaders immediately if you need an extension or hiatus!").queue();
                        }
                    }
                }
                if (now.getHour() >= 1) {
                    String currentDayKey = now.getYear() + "-" + now.getDayOfYear() + "-STAFFPING";
                    
                    if (!currentDayKey.equals(db.getBotState("last_staff_ping"))) {
                        db.setBotState("last_staff_ping", currentDayKey);
                        
                        String staffChannelId = System.getenv("STAFF_SCHEDULE_CHANNEL_ID");
                        TextChannel staffChannel = staffChannelId != null ? jda.getTextChannelById(staffChannelId) : null;
                        
                        if (staffChannel != null) {
                            int currentDayOfWeek = now.getDayOfWeek().getValue(); // 1 = Mon, 7 = Sun
                            List<String[]> dailyTasks = db.getDailySchedule(currentDayOfWeek);
                            
                            if (!dailyTasks.isEmpty()) {
                                StringBuilder pingMessage = new StringBuilder();
                                pingMessage.append("# 📢 ✦ DAILY STAFF DUTIES ✦\n");
                                pingMessage.append("Good morning team! Here are your scheduled tasks for today (**").append(now.getDayOfWeek().name()).append("**):\n\n");
                                
                                for (String[] task : dailyTasks) {
                                    pingMessage.append("﹢◞ ꒰ **").append(task[0]).append(":** <@").append(task[1]).append("> ꒱ ◟﹢\n");
                                }
                                
                                pingMessage.append("\n*Please ensure your tasks are completed on time! Have a great day! 🎀*");
                                staffChannel.sendMessage(pingMessage.toString()).queue();
                            }
                        }
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 12, TimeUnit.HOURS); 
    }
}