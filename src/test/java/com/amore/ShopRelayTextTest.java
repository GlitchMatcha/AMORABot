package com.amore;

import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShopRelayTextTest {

    private static final String HEADER = "✦ **New Drop by** <@123456789012345678> (from <#123456789012345678>):\n\n";
    private static final String FOOTER = "\n🔗 [**Click here to visit shop thread**](https://discord.com/channels/1/2)\n"
            + "-# ꒰ ⌾ ꒱ ✦ **Creator Rating:** ★★★★★ 4.5/5 `(4 reviews)`";

    @Test
    @DisplayName("Long shop template with several images stays within Discord's limit and keeps the footer")
    public void longTemplateIsBoundedAndKeepsFooter() {
        String template = "COMBOS :4A: 15 <:robux:111111111111111111> PACK\n".repeat(80);
        String url = "https://cdn.discordapp.com/attachments/1563091441918414900/1563091441918414901/image.png"
                + "?ex=68f9a1b2&is=68f85032&hm=" + "a".repeat(64) + "&";
        List<String> attachments = Collections.nCopies(4, url);

        String relay = ChatListener.buildRelayText(HEADER, template, attachments, FOOTER);

        assertTrue(relay.length() <= Message.MAX_CONTENT_LENGTH, "relay length " + relay.length());
        assertTrue(relay.startsWith(HEADER.trim()));
        assertTrue(relay.endsWith(FOOTER.trim()));
        assertTrue(relay.contains("...\n\n" + url), "truncated body must be followed by the attachments");
    }

    @Test
    @DisplayName("Attachments that cannot fit are dropped instead of overflowing")
    public void excessAttachmentsAreDropped() {
        List<String> attachments = Collections.nCopies(10, "https://cdn.discordapp.com/" + "x".repeat(300));

        String relay = ChatListener.buildRelayText(HEADER, "price: 25 rbx", attachments, FOOTER);

        assertTrue(relay.length() <= Message.MAX_CONTENT_LENGTH, "relay length " + relay.length());
        assertTrue(relay.endsWith(FOOTER.trim()));
    }

    @Test
    @DisplayName("Short listing is relayed verbatim")
    public void shortListingUnchanged() {
        String relay = ChatListener.buildRelayText(HEADER, "price: 25 rbx", List.of("https://cdn/a.png"), FOOTER);

        assertEquals(HEADER + "price: 25 rbx\n\nhttps://cdn/a.png\n" + FOOTER, relay);
    }
}
