package com.amore;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StatusRotator {

    private static final List<String> STATUSES = Arrays.asList(
            "AMORA ✦: Matcha Says to Type /pull",
            "AMORA ✦: Everyday's AM0RA is a place to be called Home! 🪩",
            "AMORA ✦: Matcha forgot to sleep again... ",
            "AMORA ✦: Welcome every angels! have a safestay here! 🎀",
            "AMORA ✦: Dropping Sparks in chat ✨",
            "AMORA ✦: Spread Kindness to everyone! 💖",
            "AMORA ✦: Everyone here is working hard to bring safe place ! 🫂",
            "AMORA ✦: Matcha is always here to help you! 🫶",
            "AMORA ✦: Look MUSIC RECOMMENDATION! WOOOO "
    );

    public static void start(JDA jda) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        scheduler.scheduleAtFixedRate(new Runnable() {
            private int currentIndex = 0;

            @Override
            public void run() {
                try {
                    String currentStatus = STATUSES.get(currentIndex);
                    
                    jda.getPresence().setActivity(Activity.playing(currentStatus));
                    
                    currentIndex = (currentIndex + 1) % STATUSES.size();
                } catch (Exception e) {
                    System.err.println("Failed to update status: " + e.getMessage());
                }
            }
        }, 0, 5, TimeUnit.MINUTES); 
    }
}