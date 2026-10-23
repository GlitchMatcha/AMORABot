package com.amore;

import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ShopRelayTextTest {

    private static final String HEADER = "✦ **New Drop by** <@123456789012345678> (from <#123456789012345678>):\n\n";
    private static final String FOOTER = "\n🔗 [**Click here to visit shop thread**](https://discord.com/channels/1/2)\n"
            + "-# ꒰ ⌾ ꒱ ✦ **Creator Rating:** ★★★★★ 4.5/5 `(4 reviews)`";

    @Test
    @DisplayName("Long shop template stays within Discord's limit and keeps header and footer")
    public void longTemplateIsBoundedAndKeepsFooter() {
        String template = "COMBOS :4A: 15 <:robux:111111111111111111> PACK\n".repeat(80);

        String relay = ChatListener.buildRelayText(HEADER, template, FOOTER);

        assertTrue(relay.length() <= Message.MAX_CONTENT_LENGTH, "relay length " + relay.length());
        assertTrue(relay.startsWith(HEADER.trim()));
        assertTrue(relay.endsWith(FOOTER.trim()));
        assertTrue(relay.contains("...\n\n" + FOOTER), "truncated body must be marked and followed by the footer");
    }

    @Test
    @DisplayName("Short listing is relayed verbatim")
    public void shortListingUnchanged() {
        String relay = ChatListener.buildRelayText(HEADER, "price: 25 rbx", FOOTER);

        assertEquals(HEADER + "price: 25 rbx\n\n" + FOOTER, relay);
    }

    @Test
    @DisplayName("Relayed files use a neutral name, keep the extension and spoiler flag")
    public void relayFileNameHidesOriginalName() {
        assertEquals("drop_1.png", ChatListener.relayFileName(1, "PNG", false));
        assertEquals("SPOILER_drop_2.gif", ChatListener.relayFileName(2, "gif", true));
        assertEquals("drop_3.png", ChatListener.relayFileName(3, null, false));
        assertEquals("drop_4.png", ChatListener.relayFileName(4, "../evil name", false));
    }
}
