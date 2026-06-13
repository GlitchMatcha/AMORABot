package com.amore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class ChatListener extends ListenerAdapter {

    private static final Map<String, Long> userCooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 30_000;
    private static final double DROP_CHANCE = 0.02;

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        String userId = event.getAuthor().getId();
        long now = System.currentTimeMillis();
        long lastRoll = userCooldowns.getOrDefault(userId, 0L);

        if (now - lastRoll < COOLDOWN_MS) {
            return;
        }

        userCooldowns.put(userId, now);

        if (Math.random() < DROP_CHANCE) {
            DatabaseManager db = DatabaseManager.getInstance();
            int currentSparks = db.getSparks(userId);
            db.updateSparks(userId, currentSparks + 1);

            event.getChannel()
                    .sendMessage("✦ *" + event.getAuthor().getAsMention()
                            + " generated a Spark from the energy in this room.* ⚡")
                    .queue(message -> message.delete().queueAfter(10, TimeUnit.SECONDS));
        }
    }
}