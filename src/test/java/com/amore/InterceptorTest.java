package com.amore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InterceptorTest {

    private CommandListener listener;

    @BeforeEach
    public void setUp() {
        listener = new CommandListener();
    }

    @Test
    @DisplayName("User screenshot: Roblox game pass markdown link must not be intercepted or corrupted")
    public void testUserScreenshotMessageNotIntercepted() {
        String message = "୨୧ hi! here's the pass for your order\n" +
                         ".\n" +
                         ":\n" +
                         ". -> [stargazer](https://www.roblox.com/game-pass/2045882826/dezi-stargazer) ౨ৎ\n" +
                         "after purchasing, please send me a screenshot of the payment together with your roblox username";

        CommandListener.InterceptResult result = listener.evaluateInterception(message);
        assertFalse(result.shouldIntercept, "Roblox game pass message should not trigger sensitive interception");
        assertEquals(message, result.censoredContent, "Content must remain completely intact without corruption");
    }

    @Test
    @DisplayName("Plain Roblox Game Pass URL must not be intercepted")
    public void testPlainRobloxGamePassUrl() {
        String message = "https://www.roblox.com/game-pass/2045882826/dezi-stargazer";
        CommandListener.InterceptResult result = listener.evaluateInterception(message);
        assertFalse(result.shouldIntercept);
        assertEquals(message, result.censoredContent);
    }

    @Test
    @DisplayName("Roblox URL without www must not be intercepted")
    public void testRobloxUrlWithoutWww() {
        String message = "https://roblox.com/game-pass/2045882826/dezi-stargazer";
        CommandListener.InterceptResult result = listener.evaluateInterception(message);
        assertFalse(result.shouldIntercept);
        assertEquals(message, result.censoredContent);
    }

    @Test
    @DisplayName("Prefixed gamepass links must not be intercepted")
    public void testPrefixedGamepassLinks() {
        String[] prefixed = {
            "pass: https://www.roblox.com/game-pass/2045882826/dezi-stargazer",
            "gamepass: https://www.roblox.com/game-pass/2045882826/dezi-stargazer",
            "link: https://www.roblox.com/game-pass/2045882826/dezi-stargazer",
            "here is the pass: https://www.roblox.com/game-pass/2045882826/dezi-stargazer"
        };

        for (String msg : prefixed) {
            CommandListener.InterceptResult result = listener.evaluateInterception(msg);
            assertFalse(result.shouldIntercept, "Should not intercept: " + msg);
            assertEquals(msg, result.censoredContent, "Should not alter: " + msg);
        }
    }

    @Test
    @DisplayName("Gamepass prices and conversational mentions must not be intercepted")
    public void testConversationalGamepassMentions() {
        String[] msgs = {
            "pass: 100 robux",
            "pass: 100",
            "gamepass: 500 R$",
            "hi, buy the game-pass please",
            "did you make the gamepass yet?",
            "i want high-quality art",
            "is this full-time or part-time?",
            "user-friendly step-by-step guide"
        };

        for (String msg : msgs) {
            CommandListener.InterceptResult result = listener.evaluateInterception(msg);
            assertFalse(result.shouldIntercept, "Should not intercept conversational message: " + msg);
            assertEquals(msg, result.censoredContent);
        }
    }

    @Test
    @DisplayName("Spoilered Roblox links must not be intercepted")
    public void testSpoileredRobloxLink() {
        String msg = "|| https://www.roblox.com/game-pass/2045882826/dezi-stargazer ||";
        CommandListener.InterceptResult result = listener.evaluateInterception(msg);
        assertFalse(result.shouldIntercept);
        assertEquals(msg, result.censoredContent);
    }

    @Test
    @DisplayName("Legitimate delivery URLs must be intercepted and censored")
    public void testDeliveryUrlsIntercepted() {
        String[] deliveryUrls = {
            "https://drive.google.com/file/d/1A2B3C4D5E6F/view?usp=sharing",
            "https://mega.nz/file/ABC123#secret_key_456",
            "https://mediafire.com/file/xyz789/commission.zip",
            "https://dropbox.com/s/sample123/asset.rar",
            "https://wetransfer.com/downloads/12345/abcdef",
            "https://catbox.moe/abc123.zip",
            "delivery: https://drive.google.com/file/d/12345/view"
        };

        for (String msg : deliveryUrls) {
            CommandListener.InterceptResult result = listener.evaluateInterception(msg);
            assertTrue(result.shouldIntercept, "Should intercept delivery URL: " + msg);
            assertTrue(result.censoredContent.contains("`[  securely hidden by AM0RA ]`"), "Should contain censor placeholder: " + result.censoredContent);
        }
    }

    @Test
    @DisplayName("Legitimate formatted license/activation keys must be intercepted")
    public void testTransactionCodesIntercepted() {
        String[] codes = {
            "ABCD-1234-EFGH",
            "X1Y2-Z3A4-B5C6-D7E8",
            "AAAAA-BBBBB-CCCCC",
            "code: ABCD-1234-EFGH",
            "key: X1Y2-Z3A4-B5C6",
            "license: ABCD-9999-WXYZ"
        };

        for (String msg : codes) {
            CommandListener.InterceptResult result = listener.evaluateInterception(msg);
            assertTrue(result.shouldIntercept, "Should intercept code: " + msg);
            assertTrue(result.censoredContent.contains("`[  securely hidden by AM0RA ]`"));
        }
    }

    @Test
    @DisplayName("Explicit passwords must be intercepted")
    public void testExplicitPasswordsIntercepted() {
        String[] passwords = {
            "password: SuperSecretPass123!",
            "passcode: 987654",
            "pin: 4321"
        };

        for (String msg : passwords) {
            CommandListener.InterceptResult result = listener.evaluateInterception(msg);
            assertTrue(result.shouldIntercept, "Should intercept password: " + msg);
            assertTrue(result.censoredContent.contains("`[  securely hidden by AM0RA ]`"));
        }
    }

    @Test
    @DisplayName("isValidTransactionCode correctly differentiates codes from English words and roblox terms")
    public void testIsValidTransactionCodeDirectly() {
        // Must be false
        assertFalse(listener.isValidTransactionCode("game-pass"));
        assertFalse(listener.isValidTransactionCode("gamepass"));
        assertFalse(listener.isValidTransactionCode("high-quality"));
        assertFalse(listener.isValidTransactionCode("full-time"));
        assertFalse(listener.isValidTransactionCode("part-time"));
        assertFalse(listener.isValidTransactionCode("step-by-step"));
        assertFalse(listener.isValidTransactionCode("https://www.roblox.com/game-pass/123"));
        assertFalse(listener.isValidTransactionCode("100 robux"));
        assertFalse(listener.isValidTransactionCode("null"));

        // Must be true
        assertTrue(listener.isValidTransactionCode("ABCD-1234-EFGH"));
        assertTrue(listener.isValidTransactionCode("X1Y2-Z3A4-B5C6-D7E8"));
        assertTrue(listener.isValidTransactionCode("AAAAA-BBBBB-CCCCC"));
        assertTrue(listener.isValidTransactionCode("sk_live_1234567890abcdef123456"));
    }
}
