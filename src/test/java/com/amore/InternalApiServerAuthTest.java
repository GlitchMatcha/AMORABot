package com.amore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InternalApiServerAuthTest {

    private static final String SECRET = "s".repeat(32);
    private static final String TIMESTAMP = "1700000000000";
    private static final String BODY = "{\"token\":\"aaaa\"}";
    // Produced by verifier/src/hmac.js sign(SECRET, TIMESTAMP, BODY): the Node and Java sides must agree.
    private static final String NODE_SIGNATURE = "a7a1a8d6e7570ffe53d103e7c561458fd6453c864afc2dd59ef073f58f608efb";
    private static final long NOW = 1_700_000_000_000L + 1_000;

    @Test
    void acceptsSignatureProducedByTheNodeVerifier() {
        assertTrue(InternalApiServer.isAuthentic(SECRET, TIMESTAMP, BODY, NODE_SIGNATURE, NOW));
    }

    @Test
    void rejectsTamperedBodyWrongSecretAndGarbageSignature() {
        assertFalse(InternalApiServer.isAuthentic(SECRET, TIMESTAMP, BODY.replace("aaaa", "bbbb"), NODE_SIGNATURE, NOW));
        assertFalse(InternalApiServer.isAuthentic("t".repeat(32), TIMESTAMP, BODY, NODE_SIGNATURE, NOW));
        assertFalse(InternalApiServer.isAuthentic(SECRET, TIMESTAMP, BODY, "not-hex", NOW));
        assertFalse(InternalApiServer.isAuthentic(SECRET, TIMESTAMP, BODY, null, NOW));
    }

    @Test
    void rejectsReplayOutsideTheClockSkewWindowInEitherDirection() {
        assertTrue(InternalApiServer.isAuthentic(SECRET, TIMESTAMP, BODY, NODE_SIGNATURE, 1_700_000_000_000L + 60_000));
        assertFalse(InternalApiServer.isAuthentic(SECRET, TIMESTAMP, BODY, NODE_SIGNATURE, 1_700_000_000_000L + 60_001));
        assertFalse(InternalApiServer.isAuthentic(SECRET, TIMESTAMP, BODY, NODE_SIGNATURE, 1_700_000_000_000L - 60_001));
        assertFalse(InternalApiServer.isAuthentic(SECRET, "garbage", BODY, NODE_SIGNATURE, NOW));
    }
}
