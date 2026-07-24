package com.amore;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.imageio.ImageIO;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;

public class ChatListener extends ListenerAdapter {

    private static final String CHAT_ACTIVITY_CHANNEL_ID = System.getenv("CHAT_ACTIVITY_CHANNEL_ID");
    private static final String ACTIVE_CHECK_CHANNEL_ID = System.getenv("ACTIVE_CHECK_CHANNEL_ID");

    private static final Map<String, Long> userCooldowns = new ConcurrentHashMap<>();
    private static final Map<String, ActiveSparkDrop> activeSparkDrops = new ConcurrentHashMap<>();
    private static final Map<String, ActivePrompt> activePrompts = new ConcurrentHashMap<>();
    
    private static final Deque<Long> processedShopMessages = new ArrayDeque<>();

    private static class ActiveCheckTracker {
        String emojiCode;
        int goal;
        boolean firstClaimed = false;
        boolean goalReached = false;
        String firstReactorId = null;
        long firstReactionTime = 0L; 
        long createdAt = System.currentTimeMillis(); 
        List<String> allReactors = new ArrayList<>(); 

        ActiveCheckTracker(String emojiCode, int goal) {
            this.emojiCode = emojiCode;
            this.goal = goal;
        }
    }
    private static final Map<String, ActiveCheckTracker> activeChecks = new ConcurrentHashMap<>();

    private static final long USER_ROLL_COOLDOWN_MS = 30_000L;
    private static final long QUESTION_COOLDOWN_MS = 6 * 60_000L;
    private static final long SPARK_COOLDOWN_MS = 30 * 60_000L;
    private static final long QUESTION_LIFETIME_MS = 2 * 60_000L;
    private static final long SPARK_LIFETIME_MS = 90_000L;

    private static final double QUESTION_CHANCE = 0.016;
    private static final double SPARK_DROP_CHANCE = 0.0015;

    private static final int RECENT_PROMPT_MEMORY = 12;
    private static final int PROMPT_REWARD_SLOTS = 3;
    private static final int PROMPT_REWARD_AMOUNT = 1;
    private static final int MIN_PROMPT_REPLY_LENGTH = 2; 

    private static volatile long lastQuestionAt = 0L;
    private static volatile long lastSparkDropAt = 0L;

    private static final List<String> WOULD_YOU_RATHER_PROMPTS = Arrays.asList(
            "Would you rather always win close games or make a dramatic comeback every time?",
            "Would you rather have unlimited creativity or perfect discipline?",
            "Would you rather be known as iconic, mysterious, or dangerously funny?",
            "Would you rather always know the right thing to say or always know when to stay silent?",
            "Would you rather relive your favorite day once a year or skip your worst day forever?",
            "Would you rather be the funniest person in the room or the calmest?",
            "Would you rather have elite luck for one day a month or average luck every day?",
            "Would you rather always be early or always be perfectly dressed?",
            "Would you rather instantly finish every task or instantly start every task?",
            "Would you rather have perfect taste in music or perfect taste in fashion?",
            "Would you rather only watch comfort rewatches or only discover new favorites?",
            "Would you rather be unforgettable online or unforgettable in person?",
            "Would you rather always have the perfect comeback or never need one?",
            "Would you rather explore the deep ocean or deep space for one day?",
            "Would you rather live in your favorite movie world or favorite game world?",
            "Would you rather have a pause button or a rewind button for real life?",
            "Would you rather always have your phone fully charged or never wait in line again?",
            "Would you rather lose your sense of time or your sense of direction for a day?",
            "Would you rather be extremely lucky or extremely charming?",
            "Would you rather know every language or play every instrument?",
            "Would you rather have a guaranteed peaceful life or a wildly exciting one?",
            "Would you rather be great at first impressions or unforgettable after five minutes?",
            "Would you rather have dream travel for free or dream food for free?",
            "Would you rather be able to nap perfectly on command or wake up perfectly on command?",
            "Would you rather always have ideal weather or ideal lighting?",
            "Would you rather read minds once a day or turn invisible once a day?",
            "Would you rather never be awkward again or never be tired again?",
            "Would you rather be absurdly photogenic or absurdly persuasive?",
            "Would you rather always get the aux or always get the best seat?",
            "Would you rather live in a penthouse in a noisy city or a quiet house with a huge garden?",
            "Would you rather know your best future moment or your worst future mistake?",
            "Would you rather have your perfect aesthetic room or your perfect wardrobe?",
            "Would you rather always have the right playlist or always have the right words?",
            "Would you rather be able to teleport locally or freeze time for ten seconds?",
            "Would you rather always feel cozy or always feel confident?",
            "Would you rather get one huge win this year or many small wins every week?"
    );

    private static final List<String> FAST_ANSWER_PROMPTS = Arrays.asList(
            "What fictional world would you survive in for exactly one week?",
            "If your vibe today had a soundtrack, what song would be playing?",
            "What's one tiny thing that instantly improves your day?",
            "If AMORA had a mascot, what should it be?",
            "What is the strongest late-night snack pick: sweet, salty, or chaotic?",
            "If you could master one skill overnight, what would it be?",
            "What is more powerful: luck, patience, or timing?",
            "If your current mood was a color, what color is it?",
            "What's the best comfort rewatch of all time?",
            "If you had to describe this room's energy in three words, what are they?",
            "What is the better flex: being early, being consistent, or being unforgettable?",
            "If you could add one harmless superpower to daily life, what would it be?",
            "What is one song you can defend with your whole life?",
            "What fictional character would survive your daily routine the worst?",
            "What is the most elite drink order of all time?",
            "What is one tiny hill you will always die on?",
            "What emoji best describes your energy right now?",
            "What is a small thing that feels weirdly luxurious?",
            "What game or hobby would you instantly get good at if you had the chance?",
            "What is the strongest main-character weather: rain, snow, sun, or thunder?",
            "What is a hobby that looks way cooler than it probably feels?",
            "What is your personal instant mood reset?",
            "Which sound is weirdly comforting to you?",
            "What is one thing you wish came with background music?",
            "What is one fictional place that feels like home even if it is not real?",
            "What everyday thing deserves a dramatic soundtrack?",
            "What color should confidence be?",
            "What is your most defended comfort food?",
            "What is one thing that always feels longer than it should?",
            "What is the best excuse to disappear for a weekend?",
            "What is one completely normal thing that still feels magical?",
            "What would your signature aura color be?",
            "What is the strongest cozy season accessory?",
            "What would your personal loading-screen tip say?",
            "What is one talent you respect every single time?",
            "What is something people underestimate until they try it?",
            "What would your dream room smell like?",
            "What is the most elite background activity while chatting online?",
            "What is one job you think you would secretly crush?",
            "What snack has the most dangerous 'just one more' energy?"
    );

    private static final List<String> THIS_OR_THAT_PROMPTS = Arrays.asList(
            "Pick one instantly: sunrise or midnight?",
            "Pick one instantly: sweet or salty?",
            "Pick one instantly: headphones or speaker?",
            "Pick one instantly: city lights or quiet countryside?",
            "Pick one instantly: tea or coffee?",
            "Pick one instantly: playlists or albums?",
            "Pick one instantly: calls or texts?",
            "Pick one instantly: staying in or going out?",
            "Pick one instantly: pink glow or blue glow?",
            "Pick one instantly: beach or mountain?",
            "Pick one instantly: chaos or structure?",
            "Pick one instantly: winter fashion or summer freedom?",
            "Pick one instantly: hoodie or jacket?",
            "Pick one instantly: arcade or bookstore?",
            "Pick one instantly: candles or fairy lights?",
            "Pick one instantly: spicy or savory?",
            "Pick one instantly: silver or gold?",
            "Pick one instantly: sunrise walk or midnight drive?",
            "Pick one instantly: fictional romance or fictional rivalry?",
            "Pick one instantly: polished aesthetic or messy charm?",
            "Pick one instantly: cats or dogs?",
            "Pick one instantly: rain sounds or ocean sounds?",
            "Pick one instantly: window seat or aisle seat?",
            "Pick one instantly: dramatic entrance or quiet impact?",
            "Pick one instantly: croissant or donut?",
            "Pick one instantly: console or PC?",
            "Pick one instantly: autumn leaves or spring bloom?",
            "Pick one instantly: ramen or pasta?",
            "Pick one instantly: notebook or notes app?",
            "Pick one instantly: deep talk or chaotic banter?"
    );

    private static final List<String> ONE_WORD_PROMPTS = Arrays.asList(
            "One word only: your mood right now?",
            "One word only: your ideal weekend?",
            "One word only: today's atmosphere?",
            "One word only: the room's current energy?",
            "One word only: your current soundtrack?",
            "One word only: what you need more of this week?",
            "One word only: your social battery status?",
            "One word only: tonight's vibe?",
            "One word only: your dream weather?",
            "One word only: your current aesthetic?",
            "One word only: your motivation level?",
            "One word only: your current food craving?",
            "One word only: the color of today?",
            "One word only: your current season?",
            "One word only: your vibe in a group chat?",
            "One word only: what this month has felt like?",
            "One word only: your brain right now?",
            "One word only: your ideal escape?",
            "One word only: your comfort zone?",
            "One word only: your late-night mood?"
    );

    private static final List<String> HOT_TAKE_PROMPTS = Arrays.asList(
            "Hot take: what is an overrated food, show, or trend?",
            "Hot take: what habit instantly makes someone seem cool?",
            "Hot take: what song should never be skipped?",
            "Hot take: what is secretly harder than people admit?",
            "Hot take: what is the most underrated comfort activity?",
            "Hot take: what makes a room instantly feel welcoming?",
            "Hot take: what is a better flex than money?",
            "Hot take: what trend deserves to disappear immediately?",
            "Hot take: what food gets too much hype?",
            "Hot take: what social rule deserves to be ignored?",
            "Hot take: what is the best kind of boring?",
            "Hot take: what game mechanic is always fun?",
            "Hot take: what aesthetic is harder to pull off than people think?",
            "Hot take: what weather is unfairly disrespected?",
            "Hot take: what tiny luxury matters more than people admit?",
            "Hot take: what makes somebody instantly memorable?",
            "Hot take: what movie genre is best with friends?",
            "Hot take: what deserves more main-character energy?",
            "Hot take: what daily habit actually changes everything?",
            "Hot take: what is the superior comfort drink?"
    );

    private static final List<String> CHAOTIC_FUN_PROMPTS = Arrays.asList(
            "You can rename Monday. What do you call it?",
            "A dragon joins the server. What role do we give it?",
            "Your aura becomes a warning label. What does it say?",
            "If this chat had a boss battle, what would phase two look like?",
            "The room gets a theme song for 24 hours. What should it be like?",
            "If your life had patch notes this week, what changed?",
            "You unlock a useless but funny passive ability. What is it?",
            "What would be the funniest fake item to sell in the AMORA shop?",
            "If your keyboard had one dramatic button, what would it do?",
            "You open a mystery door in this server. What is behind it?",
            "What would be the funniest possible title for your autobiography?",
            "If your mood had an item rarity, what rarity is it right now?",
            "You are forced to make a perfume named after today. What does it smell like?",
            "If this room became a café, what is its signature menu item?",
            "A narrator starts describing your day. What is the opening line?",
            "What would be the funniest 'do not disturb' status message?",
            "If your current brain state was a game map, what would it be called?",
            "What animal would absolutely dominate a group chat if it could type?",
            "If your stress had a fashion style, what would it wear?",
            "What fake achievement did you accidentally unlock this week?"
    );

    private static final List<String> ALL_PROMPTS = buildPromptPool();

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

    private static final Deque<String> recentPrompts = new ArrayDeque<>();
    private static final Set<String> recentPromptSet = new HashSet<>();

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

    private static class ActivePrompt {
        private final String promptText;
        private final String normalizedPromptText;
        private final long expiresAt;
        private final Set<String> rewardedUserIds = ConcurrentHashMap.newKeySet();
        private final Set<String> rewardedReplyFingerprints = ConcurrentHashMap.newKeySet();
        private volatile int rewardsGiven;
        private volatile long messageId;

        private ActivePrompt(String promptText, long expiresAt) {
            this.promptText = promptText;
            this.normalizedPromptText = normalizeForComparison(promptText);
            this.expiresAt = expiresAt;
            this.rewardsGiven = 0;
            this.messageId = 0L;
        }

        private boolean isExpired(long now) {
            return now > expiresAt;
        }

        private boolean isFull() {
            return rewardsGiven >= PROMPT_REWARD_SLOTS;
        }
    }

    private static List<String> buildPromptPool() {
        List<String> prompts = new ArrayList<>();
        prompts.addAll(WOULD_YOU_RATHER_PROMPTS);
        prompts.addAll(FAST_ANSWER_PROMPTS);
        prompts.addAll(THIS_OR_THAT_PROMPTS);
        prompts.addAll(ONE_WORD_PROMPTS);
        prompts.addAll(HOT_TAKE_PROMPTS);
        prompts.addAll(CHAOTIC_FUN_PROMPTS);
        return List.copyOf(prompts);
    }

    private void lazyCleanup() {
        long now = System.currentTimeMillis();
        activeChecks.entrySet().removeIf(entry -> (now - entry.getValue().createdAt) > TimeUnit.HOURS.toMillis(24));
    }


    private static BufferedImage fetchAvatar(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            return ImageIO.read(connection.getInputStream());
        } catch (Exception e) {
            return new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        }
    }

    private static BufferedImage makeCircle(BufferedImage img) {
        int width = img.getWidth();
        BufferedImage circle = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = circle.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.fillOval(0, 0, width, width);
        g2d.setComposite(AlphaComposite.SrcIn);
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();
        return circle;
    }

    private static byte[] generateProfileCard(String username, String avatarUrl, String bannerUrl, int sparks) {
        try {
            int width = 380;
            int height = 480; 
            BufferedImage card = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = card.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color bgColor = new Color(38, 35, 43);
            Color boxColor = new Color(28, 25, 33);
            
            g2d.setColor(bgColor); 
            g2d.fillRoundRect(0, 0, width, height, 30, 30);

            if (bannerUrl != null) {
                BufferedImage banner = fetchAvatar(bannerUrl + "?size=512");
                g2d.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, width, 120, 30, 30));
                g2d.drawImage(banner, 0, 0, width, 120, null);
                g2d.setClip(null);
            } else {
                g2d.setColor(new Color(139, 65, 107)); 
                g2d.fillRoundRect(0, 0, width, 120, 30, 30);
                g2d.fillRect(0, 100, width, 20);
            }

            int avatarSize = 100;
            int avatarX = 20;
            int avatarY = 60;
            
            g2d.setColor(bgColor);
            g2d.fillOval(avatarX - 8, avatarY - 8, avatarSize + 16, avatarSize + 16);

            BufferedImage avatar = fetchAvatar(avatarUrl + "?size=256");
            BufferedImage circleAvatar = makeCircle(avatar);
            g2d.drawImage(circleAvatar, avatarX, avatarY, avatarSize, avatarSize, null);

            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 26));
            String safeName = username.replaceAll("[^\\p{L}\\p{N}\\p{Punct}\\s]", "").trim();
            if (safeName.isEmpty()) safeName = "Player";
            g2d.drawString(safeName, 20, 200);

            g2d.setColor(boxColor); 
            g2d.fillRoundRect(20, 230, 340, 50, 16, 16);
            
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 14));
            g2d.drawString("Game Collection", 35, 260);

            g2d.setColor(new Color(45, 42, 50));
            g2d.fillRoundRect(310, 240, 35, 30, 8, 8);
            g2d.setColor(new Color(180, 175, 185));
            g2d.drawString("+3", 317, 260);

            g2d.setColor(boxColor);
            g2d.fillRoundRect(20, 300, 150, 32, 16, 16);
            g2d.setColor(new Color(255, 105, 180)); 
            g2d.fillOval(28, 308, 16, 16);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g2d.drawString("Event Winner", 52, 322);

            g2d.setColor(boxColor);
            g2d.fillRoundRect(20, 345, 130, 32, 16, 16);
            g2d.setColor(new Color(88, 101, 242)); 
            g2d.fillOval(28, 353, 16, 16);
            g2d.setColor(Color.WHITE);
            g2d.drawString(sparks + " Sparks", 52, 367);

            g2d.setColor(new Color(139, 44, 94)); 
            g2d.fillRoundRect(20, 410, 340, 45, 12, 12);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 16));
            g2d.drawString("View Profile", 145, 438);

            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(card, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null; 
        }
    }

    private void handleInvisibleShopTracker(net.dv8tion.jda.api.entities.Message message, net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion channel, net.dv8tion.jda.api.entities.User author) {
        if (!channel.getType().isThread()) return;
        
        net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel thread = channel.asThreadChannel();
        String parentId = thread.getParentChannel().getId();
        
        String shopForumId1 = System.getenv("SHOP_FORUM_CHANNEL_ID");
        String shopForumId2 = System.getenv("SHOP_FORUM_CHANNEL_ID_2"); 

        boolean isShopForum = (shopForumId1 != null && parentId.equals(shopForumId1)) || 
                              (shopForumId2 != null && parentId.equals(shopForumId2));

        if (!isShopForum) return;
        if (!author.getId().equals(thread.getOwnerId())) return;
        
        boolean alreadyLogged = message.getReactions().stream()
                .anyMatch(r -> r.getEmoji().getName().equals("✅") && r.isSelf());
        if (alreadyLogged) return;

        String rawContent = message.getContentRaw();
        String normalizedContent = java.text.Normalizer.normalize(rawContent, java.text.Normalizer.Form.NFKC).toLowerCase();
        
        boolean isExplicitListing = java.util.regex.Pattern.compile("(?i)(price|cost|selling)[^0-9]{0,30}\\d+").matcher(normalizedContent).find() 
                                 || java.util.regex.Pattern.compile("(?i)\\d+[^a-zA-Z0-9]{0,15}(robux|rbx|r\\$|<a?:[^>]*robux[^>]*>)").matcher(normalizedContent).find()
                                 || normalizedContent.contains("🛒") 
                                 || normalizedContent.contains("🏷️");
        
        if (isExplicitListing) {
            long msgId = message.getIdLong();
            
            synchronized (processedShopMessages) {
                if (processedShopMessages.contains(msgId)) return;
                processedShopMessages.addLast(msgId);
                if (processedShopMessages.size() > 500) processedShopMessages.removeFirst(); 
            }

            DatabaseManager.getInstance().incrementCreatorListed(author.getId());
            message.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("✅")).queue();
            
            List<net.dv8tion.jda.api.entities.channel.forums.ForumTag> appliedTags = thread.getAppliedTags();
            List<net.dv8tion.jda.api.interactions.components.buttons.Button> pingButtons = new ArrayList<>();
            
            String pingOutfits = System.getenv("PING_OUTFITS");
            String pingLyrics = System.getenv("PING_LYRICS");
            String pingFaces = System.getenv("PING_FACES");

            boolean canPingOutfits = false;
            boolean canPingLyrics = false;
            boolean canPingFaces = false;

            for (net.dv8tion.jda.api.entities.channel.forums.ForumTag tag : appliedTags) {
                String tagName = tag.getName().toLowerCase();
                if (tagName.contains("outfit")) canPingOutfits = true;
                if (tagName.contains("lyric")) canPingLyrics = true;
                if (tagName.contains("face")) canPingFaces = true;
            }

            if (canPingOutfits && pingOutfits != null) {
                pingButtons.add(net.dv8tion.jda.api.interactions.components.buttons.Button.primary("shopping_" + pingOutfits + "_" + author.getId(), "👗 Ping Outfits"));
            }
            if (canPingLyrics && pingLyrics != null) {
                pingButtons.add(net.dv8tion.jda.api.interactions.components.buttons.Button.primary("shopping_" + pingLyrics + "_" + author.getId(), "📝 Ping Lyrics"));
            }
            if (canPingFaces && pingFaces != null) {
                pingButtons.add(net.dv8tion.jda.api.interactions.components.buttons.Button.primary("shopping_" + pingFaces + "_" + author.getId(), "🎭 Ping Faces"));
            }

            if (!pingButtons.isEmpty()) {
                pingButtons.add(net.dv8tion.jda.api.interactions.components.buttons.Button.secondary("shopnoping_" + author.getId(), "✅ Done / Close"));
                
                channel.sendMessage(author.getAsMention() + " ✦ **Listing tracked!** Select the groups you want to ping:")
                     .addActionRow(pingButtons)
                     .queue();
            } else {
                channel.sendMessage("No Tags were detected for pinging, Pwes put your tags onto your Shop Forum >p< TYSMM >p<"
                ).queue();
            }
            EmbedBuilder orderPromptEmbed = new EmbedBuilder()
                    .setColor(new Color(255, 182, 193))
                    .setDescription(" **Want to buy this?**\nClick the button below to instantly open a private order ticket with " + author.getAsMention() + "!");

            channel.sendMessageEmbeds(orderPromptEmbed.build())
                    .addActionRow(Button.success("order_start_" + author.getId(), "🛒 Order This"))
                    .queue();
        }
    }


    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        ActiveCheckTracker check = activeChecks.get(event.getMessageId());
        if (check == null) return; 

        event.retrieveUser().queue(user -> {
            if (user.isBot()) return;

            String rawReact = event.getEmoji().getFormatted().replace("\uFE0F", "");
            String reactName = event.getEmoji().getName();
            String savedEmoji = check.emojiCode;

            boolean isMatch = false;

            if (rawReact.equals(savedEmoji)) {
                isMatch = true;
            } else if (savedEmoji.startsWith(":") && savedEmoji.endsWith(":")) {
                String cleanSaved = savedEmoji.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
                String cleanReactName = reactName.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
                
                if (!cleanSaved.isEmpty() && cleanSaved.equals(cleanReactName)) {
                    isMatch = true;
                }
            } else if (!savedEmoji.startsWith("<") && !savedEmoji.startsWith(":")) {
                if (rawReact.contains(savedEmoji) || savedEmoji.contains(rawReact)) {
                    isMatch = true;
                }
            }

            if (!isMatch) {
                return; 
            }

            String userId = user.getId();

            synchronized (check) {
                if (!check.allReactors.contains(userId)) {
                    check.allReactors.add(userId);
                } else {
                    return; 
                }

                DatabaseManager db = DatabaseManager.getInstance();

                if (!check.firstClaimed) {
                    check.firstClaimed = true;
                    check.firstReactorId = userId;
                    check.firstReactionTime = System.currentTimeMillis(); 
                    
                   int currentSparks = db.getSparks(userId);
                    int newTotal = currentSparks + 5;
                    db.updateSparks(userId, newTotal);
                    
                    int currentWins = db.getAcWins(userId); 
                    int newWins = currentWins + 1;
                    db.updateAcWins(userId, newWins);

                    net.dv8tion.jda.api.entities.Guild guild = event.getGuild();
                    
                    String todaysWinnerId = System.getenv("ROLE_TODAYS_WINNER");
                    if (todaysWinnerId != null) {
                        net.dv8tion.jda.api.entities.Role r = guild.getRoleById(todaysWinnerId);
                        if(r != null) {
                            guild.addRoleToMember(user, r).queue();
                            db.scheduleRoleRemoval(userId, todaysWinnerId, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2));
                        }
                    }

                    if (newWins == 10) {
                        String mostActiveId = System.getenv("ROLE_MOST_ACTIVE");
                        if (mostActiveId != null) {
                            net.dv8tion.jda.api.entities.Role r = guild.getRoleById(mostActiveId);
                            if(r != null) {
                                guild.addRoleToMember(user, r).queue();
                                db.scheduleRoleRemoval(userId, mostActiveId, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(14));
                                event.getChannel().sendMessage("🎉 ✦ **MILESTONE REACHED!** ✦ " + user.getAsMention() + " just hit 10 Active Check wins and unlocked the **MOST ACTIVE** role!").queue();
                            }
                        }
                    }

                    if (newWins == 25) {
                        String superMostActiveId = System.getenv("ROLE_SUPER_MOST_ACTIVE");
                        if (superMostActiveId != null) {
                            net.dv8tion.jda.api.entities.Role r = guild.getRoleById(superMostActiveId);
                            if(r != null) {
                                guild.addRoleToMember(user, r).queue();
                                db.scheduleRoleRemoval(userId, superMostActiveId, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(14));
                                event.getChannel().sendMessage("🔥 ✦ **MILESTONE REACHED!** ✦ " + user.getAsMention() + " is unstoppable! 25 Wins grants them the **SUPER MOST ACTIVE** role!").queue();
                            }
                        }
                    }

                    if (newWins == 50) {
                        String frenzyKillerId = System.getenv("ROLE_FRENZY_KILLER");
                        if (frenzyKillerId != null) {
                            net.dv8tion.jda.api.entities.Role r = guild.getRoleById(frenzyKillerId);
                            if(r != null) {
                                guild.addRoleToMember(user, r).queue();
                                event.getChannel().sendMessage("👑 ✦ **LEGENDARY ACHIEVEMENT!** ✦ " + user.getAsMention() + " just claimed their 50th Active Check win! They have permanently earned the **ACTIVE CHECK FRENZY KILLER** title!").queue();
                            }
                        }
                    }
                    
                    user.retrieveProfile().queue(profile -> {
                        String bannerUrl = profile.getBannerUrl();
                        byte[] cardData = generateProfileCard(user.getEffectiveName(), user.getEffectiveAvatarUrl(), bannerUrl, newTotal);
                        String shoutoutText = "•  ᜊ ˚ .  ౨ **Shout out to " + user.getAsMention() + ", check out their profile! ‹3** ౿  • ᜊ ˚ .";

                        if (cardData != null) {
                            FileUpload upload = FileUpload.fromData(cardData, "profile.png");
                            
                            event.getChannel().sendFiles(upload).setContent(shoutoutText).queueAfter(5, TimeUnit.SECONDS, 
                                success -> {}, 
                                error -> event.getChannel().sendMessage("⚠️ **ERROR:** Check 'Attach Files' permissions for AMORA.").queue()
                            );
                        } else {
                            net.dv8tion.jda.api.EmbedBuilder firstEmbed = new net.dv8tion.jda.api.EmbedBuilder()
                                    .setColor(new java.awt.Color(255, 182, 193)) 
                                    .setDescription(shoutoutText + "\n\n*( `+5 Sparks` )*");
                            event.getChannel().sendMessageEmbeds(firstEmbed.build()).queueAfter(5, TimeUnit.SECONDS);
                        }
                    }, error -> {
                        byte[] cardData = generateProfileCard(user.getEffectiveName(), user.getEffectiveAvatarUrl(), null, newTotal);
                        String shoutoutText = "•  ᜊ ˚ .  ౨ **Shout out to " + user.getAsMention() + ", check out their profile! ‹3** ౿  • ᜊ ˚ .";
                        
                        if (cardData != null) {
                            FileUpload upload = FileUpload.fromData(cardData, "profile.png");
                            event.getChannel().sendFiles(upload).setContent(shoutoutText).queueAfter(5, TimeUnit.SECONDS);
                        }
                    });
                } 

               if (check.allReactors.size() >= check.goal && !check.goalReached) {
                    check.goalReached = true;
                    List<String> lotteryPool = new ArrayList<>(check.allReactors);
                    if (check.firstReactorId != null) {
                        lotteryPool.remove(check.firstReactorId);
                    }
                    
                    int numWinners = (int) (lotteryPool.size() * 0.25);

                    if (numWinners > 0) {
                        java.util.Collections.shuffle(lotteryPool);
                        
                        List<String> finalWinners = new ArrayList<>(lotteryPool.subList(0, numWinners));
                        StringBuilder winnersMentions = new StringBuilder();
                        
                        net.dv8tion.jda.api.entities.Guild guild = event.getGuild();

                        for (String winnerId : finalWinners) {
                            winnersMentions.append("<@").append(winnerId).append("> ");
                            
                            int cur = db.getSparks(winnerId);
                            db.updateSparks(winnerId, cur + 3);

                            int currentWins = db.getAcWins(winnerId);
                            int newWins = currentWins + 1;
                            db.updateAcWins(winnerId, newWins);

                            guild.retrieveMemberById(winnerId).queue(member -> {
                                String todaysWinnerId = System.getenv("ROLE_TODAYS_WINNER");
                                if (todaysWinnerId != null) {
                                    net.dv8tion.jda.api.entities.Role r = guild.getRoleById(todaysWinnerId);
                                    if(r != null) {
                                        guild.addRoleToMember(member, r).queue();
                                        db.scheduleRoleRemoval(winnerId, todaysWinnerId, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2));
                                    }
                                }

                                if (newWins == 10) {
                                    String mostActiveId = System.getenv("ROLE_MOST_ACTIVE");
                                    if (mostActiveId != null) {
                                        net.dv8tion.jda.api.entities.Role r = guild.getRoleById(mostActiveId);
                                        if(r != null) {
                                            guild.addRoleToMember(member, r).queue();
                                            db.scheduleRoleRemoval(winnerId, mostActiveId, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(14));
                                            event.getChannel().sendMessage("🎉 ✦ **MILESTONE REACHED!** ✦ " + member.getAsMention() + " just hit 10 Active Check wins and unlocked the **MOST ACTIVE** role!").queue();
                                        }
                                    }
                                }

                                if (newWins == 25) {
                                    String superMostActiveId = System.getenv("ROLE_SUPER_MOST_ACTIVE");
                                    if (superMostActiveId != null) {
                                        net.dv8tion.jda.api.entities.Role r = guild.getRoleById(superMostActiveId);
                                        if(r != null) {
                                            guild.addRoleToMember(member, r).queue();
                                            db.scheduleRoleRemoval(winnerId, superMostActiveId, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(14));
                                            event.getChannel().sendMessage("🔥 ✦ **MILESTONE REACHED!** ✦ " + member.getAsMention() + " is unstoppable! 25 Wins grants them the **SUPER MOST ACTIVE** role!").queue();
                                        }
                                    }
                                }

                                if (newWins == 50) {
                                    String frenzyKillerId = System.getenv("ROLE_FRENZY_KILLER");
                                    if (frenzyKillerId != null) {
                                        net.dv8tion.jda.api.entities.Role r = guild.getRoleById(frenzyKillerId);
                                        if(r != null) {
                                            guild.addRoleToMember(member, r).queue();
                                            event.getChannel().sendMessage("👑 ✦ **LEGENDARY ACHIEVEMENT!** ✦ " + member.getAsMention() + " just claimed their 50th Active Check win! They have permanently earned the **ACTIVE CHECK FRENZY KILLER** title!").queue();
                                        }
                                    }
                                }
                            }, error -> {});
                        }

                        net.dv8tion.jda.api.EmbedBuilder lotteryEmbed = new net.dv8tion.jda.api.EmbedBuilder()
                                .setColor(new java.awt.Color(138, 43, 226)) 
                                .setDescription("✧ ˚ · .  ⊹ ࣪ ﹏𓊈 \n\n" +
                                                "🎊 **Secondary Active Check Winners:** " + winnersMentions.toString() + "\n" +
                                                "*(Chosen randomly from the party!)*\n\n" +
                                                "🎐 `+3 Sparks` & the Active Check Winner role have been awarded! ⋆.ೃ࿔");

                        long elapsed = System.currentTimeMillis() - check.firstReactionTime;
                        long delayMs = 9000L - elapsed;
                        if (delayMs < 0) delayMs = 0; 

                        event.getChannel().sendMessageEmbeds(lotteryEmbed.build()).queueAfter(delayMs, TimeUnit.MILLISECONDS, message -> {
                            
                            for (String winnerId : finalWinners) {
                                event.getJDA().retrieveUserById(winnerId).queue(wUser -> {
                                    wUser.retrieveProfile().queue(profile -> {
                                        String bannerUrl = profile.getBannerUrl();
                                        int updatedSparks = db.getSparks(winnerId);
                                        
                                        byte[] cardData = generateProfileCard(wUser.getEffectiveName(), wUser.getEffectiveAvatarUrl(), bannerUrl, updatedSparks);
                                        if (cardData != null) {
                                            FileUpload upload = FileUpload.fromData(cardData, "winner_profile.png");
                                            event.getChannel().sendFiles(upload).setContent("✨ ✦ **Secondary Winner Canvas:** " + wUser.getAsMention() + " ✦ ✨").queue();
                                        }
                                    }, error -> {
                                        int updatedSparks = db.getSparks(winnerId);
                                        byte[] cardData = generateProfileCard(wUser.getEffectiveName(), wUser.getEffectiveAvatarUrl(), null, updatedSparks);
                                        if (cardData != null) {
                                            FileUpload upload = FileUpload.fromData(cardData, "winner_profile.png");
                                            event.getChannel().sendFiles(upload).setContent("✨ ✦ **Secondary Winner Canvas:** " + wUser.getAsMention() + " ✦ ✨").queue();
                                        }
                                    });
                                });
                            }
                        });
                    }

                    activeChecks.remove(event.getMessageId());
                }
            }
        });
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) {
            return;
        }
        lazyCleanup();
        
        handleInvisibleShopTracker(event.getMessage(), event.getChannel(), event.getAuthor());

        String currentChannelId = event.getChannel().getId();
        String rawContent = event.getMessage().getContentRaw();
        DatabaseManager db = DatabaseManager.getInstance();
        
        String trigger = db.getBotState("ac_trigger");
        if (trigger == null) trigger = "ACTIVITY CHECK";

        String cleanContent = Normalizer.normalize(rawContent, Normalizer.Form.NFKC).replaceAll("\\p{Z}", " ");
        
        String ultraCleanContent = cleanContent.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
        String ultraCleanTrigger = trigger.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();

        if (ultraCleanContent.contains(ultraCleanTrigger)) {
            
            if (ACTIVE_CHECK_CHANNEL_ID == null || !currentChannelId.equals(ACTIVE_CHECK_CHANNEL_ID)) {
                return; 
            }

            String reactPhrase = db.getBotState("ac_react");
            if (reactPhrase == null) reactPhrase = "React with";
            
            String goalPhrase = db.getBotState("ac_goal");
            if (goalPhrase == null) goalPhrase = "Goal";

            String reactRegex = java.util.Arrays.stream(reactPhrase.trim().split("\\s+"))
                                  .map(Pattern::quote)
                                  .collect(java.util.stream.Collectors.joining("\\s*"));
            String goalRegex = java.util.Arrays.stream(goalPhrase.trim().split("\\s+"))
                                 .map(Pattern::quote)
                                 .collect(java.util.stream.Collectors.joining("\\s*"));

            Matcher emojiMatcher = Pattern.compile("(?i)" + reactRegex + ".*?(<a?:[a-zA-Z0-9_\\-~]+:\\d+>|:[a-zA-Z0-9_\\-~]+:|[^\\s\\p{L}\\p{N}\\p{Punct}]+)").matcher(cleanContent);
            Matcher goalMatcher = Pattern.compile("(?i)" + goalRegex + "[^0-9]*(\\d+)").matcher(cleanContent);

            if (emojiMatcher.find() && goalMatcher.find()) {
                String emojiStr = emojiMatcher.group(1).replace("\uFE0F", "");
                
                int finalGoal = 1;
                try {
                    int originalGoal = Integer.parseInt(goalMatcher.group(1));
                    finalGoal = originalGoal;
                    if (originalGoal > 1 && originalGoal < 5) {
                        finalGoal = 1;
                    }
                } catch (NumberFormatException e) {
                    finalGoal = 1;
                }
                ActiveCheckTracker tracker = activeChecks.get(event.getMessageId());
                if (tracker != null) {
                    tracker.emojiCode = emojiStr;
                    tracker.goal = finalGoal;
                } else {
                    activeChecks.put(event.getMessageId(), new ActiveCheckTracker(emojiStr, finalGoal));
                }
            }
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) {
            return;
        }
        lazyCleanup();

        handleInvisibleShopTracker(event.getMessage(), event.getChannel(), event.getAuthor());

        String currentChannelId = event.getChannel().getId();
        String rawContent = event.getMessage().getContentRaw();
        DatabaseManager db = DatabaseManager.getInstance();
        
        String trigger = db.getBotState("ac_trigger");
        if (trigger == null) trigger = "ACTIVITY CHECK";

        String cleanContent = Normalizer.normalize(rawContent, Normalizer.Form.NFKC).replaceAll("\\p{Z}", " ");
        
        String ultraCleanContent = cleanContent.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
        String ultraCleanTrigger = trigger.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();

        if (ultraCleanContent.contains(ultraCleanTrigger)) {
            
            if (ACTIVE_CHECK_CHANNEL_ID == null || !currentChannelId.equals(ACTIVE_CHECK_CHANNEL_ID)) {
                event.getChannel().sendMessage("⚠️ **System Glitch:** Activity Check detected, but this channel (`" + currentChannelId + "`) is NOT the configured `ACTIVE_CHECK_CHANNEL_ID` in Render! Ignoring.").queue();
                return;
            }

            String reactPhrase = db.getBotState("ac_react");
            if (reactPhrase == null) reactPhrase = "React with";
            
            String goalPhrase = db.getBotState("ac_goal");
            if (goalPhrase == null) goalPhrase = "Goal";

            String reactRegex = java.util.Arrays.stream(reactPhrase.trim().split("\\s+"))
                                  .map(Pattern::quote)
                                  .collect(java.util.stream.Collectors.joining("\\s*"));
            String goalRegex = java.util.Arrays.stream(goalPhrase.trim().split("\\s+"))
                                 .map(Pattern::quote)
                                 .collect(java.util.stream.Collectors.joining("\\s*"));

            Matcher emojiMatcher = Pattern.compile("(?i)" + reactRegex + ".*?(<a?:[a-zA-Z0-9_\\-~]+:\\d+>|:[a-zA-Z0-9_\\-~]+:|[^\\s\\p{L}\\p{N}\\p{Punct}]+)").matcher(cleanContent);
            Matcher goalMatcher = Pattern.compile("(?i)" + goalRegex + "[^0-9]*(\\d+)").matcher(cleanContent);

            if (emojiMatcher.find() && goalMatcher.find()) {
                String emojiStr = emojiMatcher.group(1).replace("\uFE0F", "");
                
                int finalGoal = 1;
                try {
                    int originalGoal = Integer.parseInt(goalMatcher.group(1));
                    finalGoal = originalGoal;

                    if (originalGoal > 1 && originalGoal < 5) {
                        finalGoal = 1;
                        event.getChannel().sendMessage("⚠️ ⊹₊ ˚ **System Note for " + event.getAuthor().getAsMention() + ":** You set the goal to `" + originalGoal + "`, but the RNG Bonus Loot requires a minimum of `5` players! I have automatically converted the goal to `1` so your players don't have to wait unnecessarily. 👾🎀").queue();
                    }
                } catch (NumberFormatException e) {
                    finalGoal = 1;
                    event.getChannel().sendMessage("⚠️ ⊹₊ ˚ **System Note for " + event.getAuthor().getAsMention() + ":** The goal number you entered is impossibly huge! I have safely defaulted the goal to `1` to prevent a system crash. 👾🎀").queue();
                }

                activeChecks.put(event.getMessageId(), new ActiveCheckTracker(emojiStr, finalGoal));
            } else {
                event.getChannel().sendMessage("⚠️ **System Glitch:** Trigger recognized, but I could not extract the Emoji or Goal. Please check formatting!").queue();
            }
            return; 
        }

        if (CHAT_ACTIVITY_CHANNEL_ID != null
                && !CHAT_ACTIVITY_CHANNEL_ID.isBlank()
                && !currentChannelId.equals(CHAT_ACTIVITY_CHANNEL_ID)) {
            return;
        }

        if (handleSparkClaim(event)) {
            return;
        }

        handlePromptReplyReward(event);

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

    private void handlePromptReplyReward(MessageReceivedEvent event) {
        String channelId = event.getChannel().getId();
        ActivePrompt activePrompt = activePrompts.get(channelId);

        if (activePrompt == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (activePrompt.isExpired(now) || activePrompt.isFull()) {
            activePrompts.remove(channelId, activePrompt);
            return;
        }

        if (event.getMessage().getMessageReference() == null || 
            event.getMessage().getMessageReference().getMessageIdLong() != activePrompt.messageId) {
            return; 
        }

        String userId = event.getAuthor().getId();
        String rawContent = event.getMessage().getContentRaw();
        String trimmed = rawContent == null ? "" : rawContent.trim();

        if (!isValidPromptReply(trimmed, activePrompt)) {
            return;
        }

        String fingerprint = fingerprintReply(trimmed);

        synchronized (activePrompt) {
            long checkNow = System.currentTimeMillis();

            if (activePrompt.isExpired(checkNow) || activePrompt.isFull()) {
                activePrompts.remove(channelId, activePrompt);
                return;
            }

            if (activePrompt.rewardedUserIds.contains(userId)) {
                return;
            }

            if (activePrompt.rewardedReplyFingerprints.contains(fingerprint)) {
                return;
            }

            activePrompt.rewardedUserIds.add(userId);
            activePrompt.rewardedReplyFingerprints.add(fingerprint);
            activePrompt.rewardsGiven++;

            DatabaseManager db = DatabaseManager.getInstance();
            int currentSparks = db.getSparks(userId);
            db.updateSparks(userId, currentSparks + PROMPT_REWARD_AMOUNT);

            int slotNumber = activePrompt.rewardsGiven;
            int remaining = Math.max(0, PROMPT_REWARD_SLOTS - activePrompt.rewardsGiven);

            event.getChannel()
                    .sendMessage("💫 " + event.getAuthor().getAsMention()
                            + " gave a valid room-prompt reply and earned **" + PROMPT_REWARD_AMOUNT + " Spark**."
                            + " (`" + slotNumber + "/" + PROMPT_REWARD_SLOTS + "` claimed"
                            + (remaining > 0 ? ", `" + remaining + "` left)" : ")"))
                    .queue(message -> message.delete().queueAfter(10, TimeUnit.SECONDS));

            if (activePrompt.isFull()) {
                activePrompts.remove(channelId, activePrompt);

                if (activePrompt.messageId != 0L) {
                    event.getChannel().retrieveMessageById(activePrompt.messageId).queue(
                            msg -> msg.editMessage(msg.getContentRaw()
                                            + "\n\n✨ **Prompt rewards are fully claimed.**")
                                    .queue(
                                            edited -> edited.delete().queueAfter(10, TimeUnit.SECONDS),
                                            error -> {
                                            }),
                            error -> {
                            });
                }
            }
        }
    }

    private boolean isValidPromptReply(String content, ActivePrompt activePrompt) {
        if (content == null || content.isBlank()) {
            return false;
        }

        if (content.length() < MIN_PROMPT_REPLY_LENGTH) {
            return false;
        }

        if (!containsLetterOrDigit(content)) {
            return false;
        }

        String normalizedReply = normalizeForComparison(content);

        if (normalizedReply.isBlank()) {
            return false;
        }

        if (normalizedReply.equals(activePrompt.normalizedPromptText)) {
            return false;
        }

        if (normalizedReply.length() < MIN_PROMPT_REPLY_LENGTH) {
            return false;
        }

        if (isLowEffortReply(normalizedReply)) {
            return false;
        }

        return true;
    }

    private boolean containsLetterOrDigit(String content) {
        for (int i = 0; i < content.length(); i++) {
            if (Character.isLetterOrDigit(content.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isLowEffortReply(String normalizedReply) {
        List<String> blockedExactReplies = Arrays.asList(
                "hi",
                "hello",
                "hey",
                "yo",
                "idk",
                "i dont know",
                "dont know",
                "no idea",
                "maybe",
                "yes",
                "no",
                "ok",
                "k",
                "lol",
                "lmao",
                "same"
        );

        if (blockedExactReplies.contains(normalizedReply)) {
            return true;
        }

        String compact = normalizedReply.replace(" ", "");
        if (compact.length() < MIN_PROMPT_REPLY_LENGTH) {
            return true;
        }

        return isSingleCharacterSpam(compact);
    }

    private boolean isSingleCharacterSpam(String value) {
        if (value.isEmpty()) {
            return true;
        }

        char first = value.charAt(0);
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeForComparison(String value) {
        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();

        normalized = normalized.replaceAll("[^\\p{L}\\p{N}\\s]", "");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private String fingerprintReply(String reply) {
        return normalizeForComparison(reply);
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
        String channelId = event.getChannel().getId();
        ActivePrompt existingPrompt = activePrompts.get(channelId);

        if (existingPrompt != null) {
            if (!existingPrompt.isExpired(now) && !existingPrompt.isFull()) {
                return false;
            }
            activePrompts.remove(channelId, existingPrompt);
        }

        String prompt;

        synchronized (ChatListener.class) {
            if (now - lastQuestionAt < QUESTION_COOLDOWN_MS) {
                return false;
            }

            if (ThreadLocalRandom.current().nextDouble() >= QUESTION_CHANCE) {
                return false;
            }

            lastQuestionAt = now;
            prompt = pickPromptWithoutRecentRepeats();
        }

        long expiresAt = now + QUESTION_LIFETIME_MS;
        long unix = Instant.ofEpochMilli(expiresAt).getEpochSecond();

        ActivePrompt activePrompt = new ActivePrompt(prompt, expiresAt);
        activePrompts.put(channelId, activePrompt);

        event.getChannel()
                .sendMessage("💬 **AMORA Room Prompt**\n"
                        + prompt + "\n\n"
                        + "First **" + PROMPT_REWARD_SLOTS + "** unique valid replies earn **"
                        + PROMPT_REWARD_AMOUNT + " Spark** each.\n"
                        + "*(You MUST use Discord's 'Reply' feature on this message to answer!)*\n"
                        + "Fades <t:" + unix + ":R>.")
                .queue(message -> {
                    activePrompt.messageId = message.getIdLong();
                    message.delete().queueAfter(QUESTION_LIFETIME_MS, TimeUnit.MILLISECONDS);
                }, error -> activePrompts.remove(channelId, activePrompt));

        return true;
    }

    private String pickPromptWithoutRecentRepeats() {
        List<String> available = new ArrayList<>();

        for (String prompt : ALL_PROMPTS) {
            if (!recentPromptSet.contains(prompt)) {
                available.add(prompt);
            }
        }

        if (available.isEmpty()) {
            recentPrompts.clear();
            recentPromptSet.clear();
            available.addAll(ALL_PROMPTS);
        }

        String chosen = available.get(ThreadLocalRandom.current().nextInt(available.size()));
        rememberPrompt(chosen);
        return chosen;
    }

    private void rememberPrompt(String prompt) {
        recentPrompts.addLast(prompt);
        recentPromptSet.add(prompt);

        while (recentPrompts.size() > RECENT_PROMPT_MEMORY) {
            String removed = recentPrompts.removeFirst();
            recentPromptSet.remove(removed);
        }
    }
}