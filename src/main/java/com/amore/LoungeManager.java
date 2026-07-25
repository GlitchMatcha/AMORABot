package com.amore;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class LoungeManager {

    private static final String LOUNGE_CHANNEL_ID = System.getenv("LOUNGE_CHANNEL_ID");
    private static final String ROLES_CHANNEL_ID = System.getenv("ROLES_CHANNEL_ID");

    private static final List<String> QUOTES = Arrays.asList(
            "No act of kindness, no matter how small, is ever wasted.",
            "Your potential is endless. Go do what you were created to do.",
            "Spread love everywhere you go. Let no one ever come to you without leaving happier.",
            "The energy you put into the world always finds its way back to you.",
            "Keep your face always toward the sunshine—and shadows will fall behind you.",
            "Extraordinary things are always hiding in places people never think to look.",
            "Success is not final, failure is not fatal: it is the courage to continue that counts.",
            "You are enough just as you are.",
            "Even the smallest spark can light up an entire room.",
            "Be the reason someone smiles today.",
            "Focus on the step in front of you, not the whole staircase.",
            "Growth is quiet, slow, and completely beautiful.",
            "Do everything with a good heart and expect nothing in return, and you will never be disappointed.",
            "Your vibe attracts your tribe.",
            "Take a deep breath. You are doing better than you think.",
            "Kindness is a language which the deaf can hear and the blind can see.",
            "Every day is a fresh start to become exactly who you want to be.",
            "Throw kindness around like confetti.",
            "The world is a little brighter because you are in it.",
            "Rest if you must, but don't you quit.",
            "Every great masterpiece started as a blank canvas. Keep creating.",
            "Your words have power. Use them to heal, not to hurt.",
            "A single warm greeting can change the trajectory of someone's entire day.",
            "The best view comes after the hardest climb.",
            "Drink some water, stretch your shoulders, and remember how far you have come.",
            "There is magic in your ordinary days. Don't forget to look for it.",
            "Never underestimate the impact of simply showing up and trying.",
            "Beautiful things take time. Trust your own pacing.",
            "You don't have to be perfect to be a light in someone's life.",
            "Softness is not weakness; it is a profound kind of strength.",
            "A flower does not think of competing with the flower next to it. It just blooms.",
            "Remember to celebrate your small victories. They add up to massive achievements.",
            "It costs nothing to be kind, but its value is infinite.",
            "Give yourself the same grace and patience you so freely give to others.",
            "Stars can't shine without darkness. Your struggles are just setting the stage.",
            "Behind every brilliant performance is hours of unseen dedication.",
            "The world needs your unique frequency. Keep broadcasting.",
            "Make someone feel seen today. It is the greatest gift you can offer.",
            "Your art, your code, your dreams—they all matter. Keep building.",
            "Even a mad scientist has to take a break for tea. Rest your mind.",
            "You are the architect of your own universe. Design it beautifully.",
            "Every line of code, every brushstroke, every note brings your vision closer to reality.",
            "Let your actions speak with the volume of a thousand stage lights.",
            "Do not let the fear of striking out keep you from playing the game.",
            "Empathy is the most advanced technology we possess.",
            "Some days you are the spotlight, some days you are the crew. Both are essential.",
            "Find your rhythm. The world will learn to dance to your beat.",
            "A cup of warm matcha and a good thought can reset any bad day.",
            "The most revolutionary thing you can do is aggressively believe in yourself.",
            "Mistakes are just data. Use them to fuel your next experiment.",
            "Let go of what you can't control and channel your energy into what you can create.",
            "True leaders don't create followers; they create more leaders.",
            "Wear your confidence like your favorite outfit—boldly and without apology.",
            "If the plan doesn't work, change the plan, but never the goal.",
            "Sometimes the most productive thing you can do is absolutely nothing.",
            "Your voice is a melody; don't let the noise of the world drown it out.",
            "There is a whole universe inside your mind waiting to be rendered.",
            "A little bit of chaos is required to invent something truly original.",
            "Be a safe haven in a world that can sometimes feel too loud.",
            "You are capable of amazing things. Believe the hype about yourself.",
            "Treat your energy like a precious resource. Invest it where it grows.",
            "The stage is yours whenever you decide to step onto it.",
            "Keep your heart open. That is where the light gets in.",
            "Nothing is impossible when you break it down into smaller pieces.",
            "Let your passion be louder than your doubts.",
            "Even the grandest digital worlds start with a single polygon.",
            "Breathe in courage, exhale fear. You've got this.",
            "Radiate positivity. It is entirely contagious.",
            "There is no timeline for your success. You are exactly where you need to be.",
            "Teach others with patience. We were all beginners once.",
            "The best outfits are the ones worn with unapologetic confidence.",
            "Don't just chase your dreams. Engineer them into reality.",
            "Every great routine starts with a single, hesitant step. Keep dancing.",
            "You don't need a grand audience to put on a spectacular show.",
            "True brilliance often looks like madness before it is understood.",
            "If you stumble, make it part of the choreography.",
            "Connect with someone today. We are all just walking each other home.",
            "Your capacity to learn is infinite. Never stop exploring.",
            "Leave a trail of stardust wherever you go.",
            "It takes courage to be kind in a world that can be so cold. Be brave.",
            "Don't wait for the perfect moment. Take the moment and make it perfect.",
            "Quiet progress is still progress. Keep moving forward.",
            "You are writing your own story. Make this chapter a good one.",
            "Lift others up. The sky is big enough for all of us to shine.",
            "A masterpiece takes time. Don't rush your own rendering process.",
            "Embrace the glitches. They often lead to the best features.",
            "Keep shining. Someone out there is navigating by your light.",
            "Take pride in the things you build, no matter how small they seem.",
            "Today is a great day to learn something entirely new.",
            "The energy of a room shifts the moment a kind person walks in. Be that person.",
            "Surround yourself with people who talk about visions and ideas.",
            "Pour love into your work, and your work will pour love into the world.",
            "Let your imagination run wild. Reality will catch up eventually.",
            "Even the most complex equations have a solution. Keep solving.",
            "Be a voice of encouragement in a chorus of critics.",
            "When you share your knowledge, you multiply your impact.",
            "Find comfort in the process, not just the finished project.",
            "Keep your standards high and your compassion even higher.",
            "You hold the blueprint to your happiness.",
            "Never apologize for being enthusiastic about the things you love.",
            "Harmony is created when different voices finally sing together.",
            "Take a moment to appreciate the aesthetic of the world around you.",
            "If you can't find the sunshine, be the sunshine.",
            "Every challenge is just a puzzle waiting for your specific genius.",
            "Your journey is uniquely yours. Embrace every single pixel of it.",
            "DEAD Fish Quote: You arent alone, there are always plenty of fishes in the sea, there to help you . Ride the waves, follow the current, chase the horizon. There is always sunshines behind the thunderstorms"
    );

    public static void start(JDA jda) {
        if (LOUNGE_CHANNEL_ID == null || LOUNGE_CHANNEL_ID.isBlank()) {
            System.out.println("⚠️ LOUNGE_CHANNEL_ID is not set. Lounge Automations are disabled.");
            return;
        }

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                TextChannel lounge = jda.getTextChannelById(LOUNGE_CHANNEL_ID);
                if (lounge != null) {
                    DatabaseManager db = DatabaseManager.getInstance();
                    
                    String savedQuoteIdStr = db.getBotState("last_quote_msg_id");
                    if (savedQuoteIdStr != null && !savedQuoteIdStr.isEmpty()) {
                        try {
                            long idToDelete = Long.parseLong(savedQuoteIdStr);
                            lounge.deleteMessageById(idToDelete).queue(success -> {}, error -> {}); 
                        } catch (NumberFormatException ignored) {}
                    }

                    String randomQuote = QUOTES.get(ThreadLocalRandom.current().nextInt(QUOTES.size()));
                    EmbedBuilder embed = new EmbedBuilder()
                            .setColor(new Color(255, 182, 193)) 
                            .setTitle("✦ AMORA QUOTE DROP ✦")
                            .setDescription("*\u201C" + randomQuote + "\u201D*")
                            .setFooter("A little spark to keep you glowing ✨", null);
                    
                    lounge.sendMessageEmbeds(embed.build()).queue(message -> {
                        db.setBotState("last_quote_msg_id", String.valueOf(message.getIdLong()));
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 2, TimeUnit.HOURS); 

        scheduler.scheduleAtFixedRate(() -> {
            try {
                TextChannel lounge = jda.getTextChannelById(LOUNGE_CHANNEL_ID);
                if (lounge != null) {
                    DatabaseManager db = DatabaseManager.getInstance();

                    String savedRoleIdStr = db.getBotState("last_role_msg_id");
                    if (savedRoleIdStr != null && !savedRoleIdStr.isEmpty()) {
                        try {
                            long idToDelete = Long.parseLong(savedRoleIdStr);
                            lounge.deleteMessageById(idToDelete).queue(success -> {}, error -> {});
                        } catch (NumberFormatException ignored) {}
                    }

                    String roleChannelMention = (ROLES_CHANNEL_ID != null && !ROLES_CHANNEL_ID.isBlank()) 
                            ? "<#" + ROLES_CHANNEL_ID + ">" 
                            : "the roles channel";

                    EmbedBuilder embed = new EmbedBuilder()
                            .setColor(new Color(138, 43, 226)) 
                            .setTitle("✦ AMORA ROLE REGISTRATION ✦")
                            .setDescription("Are you ready to step up? Don't forget to drop by " + roleChannelMention + " to officially claim your title!\n\n" +
                                    "🌸 **Guest** — For our lovely supporters here to hang out and cheer AMORA on.\n" +
                                    "💫 **Trainee** — For those actively looking to join the AMORA member force in the future.\n\n" +
                                    "*Choose your path and let's keep building something amazing together!*")
                            .setFooter("AMORA Automated Lounge Services", null);
                            
                    lounge.sendMessageEmbeds(embed.build()).queue(message -> {
                        db.setBotState("last_role_msg_id", String.valueOf(message.getIdLong()));
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 1, 3, TimeUnit.HOURS); 
    }
}