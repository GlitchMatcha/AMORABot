package com.amore;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class ChatListener extends ListenerAdapter {

    private static final String CHAT_ACTIVITY_CHANNEL_ID = System.getenv("CHAT_ACTIVITY_CHANNEL_ID");

    private static final Map<String, Long> userCooldowns = new ConcurrentHashMap<>();
    private static final Map<String, ActiveSparkDrop> activeSparkDrops = new ConcurrentHashMap<>();

    private static final long USER_ROLL_COOLDOWN_MS = 30_000L;
    private static final long QUESTION_COOLDOWN_MS = 20 * 60_000L;
    private static final long SPARK_COOLDOWN_MS = 30 * 60_000L;

    private static final long QUESTION_LIFETIME_MS = 2 * 60_000L;
    private static final long SPARK_LIFETIME_MS = 90_000L;

    private static final double QUESTION_CHANCE = 0.0075;
    private static final double SPARK_DROP_CHANCE = 0.0015;

    private static volatile long lastQuestionAt = 0L;
    private static volatile long lastSparkDropAt = 0L;

    private static final List<String> QUICK_PROMPTS = Arrays.asList(
            "Would you rather always win close games or make a dramatic comeback every time?",
            "What fictional world would you survive in for exactly one week?",
            "If your vibe today had a soundtrack, what song would be playing?",
            "Would you rather have unlimited creativity or perfect discipline?",
            "What's one tiny thing that instantly improves your day?",
            "If AMORA had a mascot, what should it be?",
            "What is the strongest late-night snack pick: sweet, salty, or chaotic?",
            "If you could master one skill overnight, what would it be?",
            "What is more powerful: luck, patience, or timing?",
            "If your current mood was a color, what color is it?",
            "Would you rather be known as iconic, mysterious, or dangerously funny?",
            "What's the best comfort rewatch of all time?",
            "If you had to describe this room's energy in three words, what are they?",
            "What is the better flex: being early, being consistent, or being unforgettable?",
            "If you could add one harmless superpower to daily life, what would it be?"
    );

    private static final List<String> MAGIC_WORDS = Arrays.asList(
            "starlight",
            "velvet",
            "matcha",
            "harmony",
            "glimmer",
            "orbit",
            "petal",
            "nova",
            "serenade",
            "aura",
            "echo",
            "lunar"
    );

    private static class ActiveSparkDrop {
        private final String magicWord;
        private final long expiresAt;
        private volatile boolean claimed;
        private volatile long messageId;

        private ActiveSparkDrop(String magicWord, long expiresAt) {
            this.magicWord = magicWord;
            this.expiresAt = expiresAt;
            this.claimed = false;
            this.messageId = 0L;
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) {
            return;
        }

        if (CHAT_ACTIVITY_CHANNEL_ID != null
                && !CHAT_ACTIVITY_CHANNEL_ID.isBlank()
                && !event.getChannel().getId().equals(CHAT_ACTIVITY_CHANNEL_ID)) {
            return;
        }

        if (handleSparkClaim(event)) {
            return;
        }

        String userId = event.getAuthor().getId();
        long now = System.currentTimeMillis();
        long lastRoll = userCooldowns.getOrDefault(userId, 0L);

        if (now - lastRoll < USER_ROLL_COOLDOWN_MS) {
            return;
        }

        userCooldowns.put(userId, now);

        if (maybeDropSpark(event, now)) {
            return;
        }

        maybePostQuestion(event, now);
    }

    private boolean handleSparkClaim(MessageReceivedEvent event) {
        String channelId = event.getChannel().getId();
        ActiveSparkDrop drop = activeSparkDrops.get(channelId);

        if (drop == null) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (drop.claimed || now > drop.expiresAt) {
            activeSparkDrops.remove(channelId, drop);
            return false;
        }

        String content = event.getMessage().getContentRaw().trim();
        if (!content.equalsIgnoreCase(drop.magicWord)) {
            return false;
        }

        synchronized (drop) {
            if (drop.claimed || System.currentTimeMillis() > drop.expiresAt) {
                return false;
            }
            drop.claimed = true;
            activeSparkDrops.remove(channelId, drop);
        }

        DatabaseManager db = DatabaseManager.getInstance();
        String winnerId = event.getAuthor().getId();
        int currentSparks = db.getSparks(winnerId);
        db.updateSparks(winnerId, currentSparks + 1);

        if (drop.messageId != 0L) {
            event.getChannel().retrieveMessageById(drop.messageId).queue(
                    msg -> msg.editMessage("⚡ **Spark Claimed!** " + event.getAuthor().getAsMention()
                                    + " typed `" + drop.magicWord + "` first and won **1 Spark**.")
                            .queue(
                                    edited -> edited.delete().queueAfter(10, TimeUnit.SECONDS),
                                    error -> {
                                    }),
                    error -> {
                    });
        }

        event.getChannel()
                .sendMessage("⚡ " + event.getAuthor().getAsMention()
                        + " captured the room Spark and gained **1 Spark**.")
                .queue(message -> message.delete().queueAfter(10, TimeUnit.SECONDS));

        return true;
    }

    private boolean maybeDropSpark(MessageReceivedEvent event, long now) {
        String channelId = event.getChannel().getId();
        ActiveSparkDrop existing = activeSparkDrops.get(channelId);

        if (existing != null) {
            if (!existing.claimed && now < existing.expiresAt) {
                return false;
            }
            activeSparkDrops.remove(channelId, existing);
        }

        synchronized (ChatListener.class) {
            if (now - lastSparkDropAt < SPARK_COOLDOWN_MS) {
                return false;
            }

            if (ThreadLocalRandom.current().nextDouble() >= SPARK_DROP_CHANCE) {
                return false;
            }

            lastSparkDropAt = now;
        }

        String magicWord = MAGIC_WORDS.get(ThreadLocalRandom.current().nextInt(MAGIC_WORDS.size()));
        long expiresAt = now + SPARK_LIFETIME_MS;
        long unix = Instant.ofEpochMilli(expiresAt).getEpochSecond();

        ActiveSparkDrop drop = new ActiveSparkDrop(magicWord, expiresAt);
        activeSparkDrops.put(channelId, drop);

        event.getChannel()
                .sendMessage("⚡ **Spark Surge detected!**\n"
                        + "First person to type `" + magicWord + "` **exactly** wins **1 Spark**.\n"
                        + "This surge expires <t:" + unix + ":R>.")
                .queue(message -> {
                    drop.messageId = message.getIdLong();
                    message.delete().queueAfter(SPARK_LIFETIME_MS, TimeUnit.MILLISECONDS);
                }, error -> activeSparkDrops.remove(channelId, drop));

        return true;
    }

    private boolean maybePostQuestion(MessageReceivedEvent event, long now) {
        synchronized (ChatListener.class) {
            if (now - lastQuestionAt < QUESTION_COOLDOWN_MS) {
                return false;
            }

            if (ThreadLocalRandom.current().nextDouble() >= QUESTION_CHANCE) {
                return false;
            }

            lastQuestionAt = now;
        }

        String prompt = QUICK_PROMPTS.get(ThreadLocalRandom.current().nextInt(QUICK_PROMPTS.size()));
        long expiresAt = now + QUESTION_LIFETIME_MS;
        long unix = Instant.ofEpochMilli(expiresAt).getEpochSecond();

        event.getChannel()
                .sendMessage("💬 **AMORA Room Prompt**\n"
                        + prompt + "\n\n"
                        + "Reply before it fades <t:" + unix + ":R>.")
                .queue(message -> message.delete().queueAfter(QUESTION_LIFETIME_MS, TimeUnit.MILLISECONDS));

        return true;
    }
}