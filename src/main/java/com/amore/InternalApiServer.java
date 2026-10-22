package com.amore;

import com.sun.net.httpserver.HttpExchange;
import java.awt.Color;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.json.JSONException;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * HTTP endpoints called only by the zero-knowledge age verifier (see /verifier).
 *
 * Every request is authenticated with HMAC-SHA256 over "{timestampMillis}.{rawBody}" using
 * VERIFIER_SHARED_SECRET and must be at most 60 seconds old. The bot owns sessions, the
 * database and role changes; the verifier only reports a proof it has checked.
 */
public class InternalApiServer {

    private static final long MAX_CLOCK_SKEW_MS = 60_000;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MIN_SECRET_LENGTH = 32;
    private static final Pattern HEX_64 = Pattern.compile("^[a-f0-9]{64}$");

    public static void startServer(int port, JDA jda) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/health", exchange -> respond(exchange, 200, new JSONObject().put("status", "ok")));
            server.createContext("/internal/age/session-check", new SignedJsonHandler(InternalApiServer::handleSessionCheck));
            server.createContext("/internal/age/complete", new SignedJsonHandler(body -> handleComplete(body, jda)));

            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            System.out.println("✦ Internal API started on port " + port);
            if (sharedSecret() == null) {
                System.err.println("⚠️ VERIFIER_SHARED_SECRET is missing or shorter than " + MIN_SECRET_LENGTH
                        + " characters: zero-knowledge age verification endpoints are disabled.");
            }
        } catch (IOException e) {
            System.out.println(" Failed to start internal API: " + e.getMessage());
        }
    }

    private record Reply(int status, JSONObject body) { }

    private interface JsonEndpoint {
        Reply handle(JSONObject body);
    }

    private static final Pattern SNOWFLAKE = Pattern.compile("^\\d{15,25}$");

    /** Describes a session without consuming it: live, owned by the given Discord user, owner banned. */
    private static Reply handleSessionCheck(JSONObject body) {
        String token = body.optString("token", "");
        String claimedUser = body.optString("discordUserId", "");
        if (!HEX_64.matcher(token).matches() || (!claimedUser.isEmpty() && !SNOWFLAKE.matcher(claimedUser).matches())) {
            return new Reply(400, new JSONObject().put("error", "bad_request"));
        }
        DatabaseManager db = DatabaseManager.getInstance();
        String owner = db.liveAgeSessionOwner(sha256Hex(token));
        boolean live = owner != null;
        return new Reply(200, new JSONObject()
                .put("live", live)
                .put("ownerMatches", live && owner.equals(claimedUser))
                .put("banned", live && db.isAgeBanned(owner)));
    }

    private static Reply handleComplete(JSONObject body, JDA jda) {
        String token = body.optString("token", "");
        String idFingerprint = body.optString("idFingerprint", "");
        String discordUserId = body.optString("discordUserId", "");
        if (!HEX_64.matcher(token).matches() || !HEX_64.matcher(idFingerprint).matches() || !SNOWFLAKE.matcher(discordUserId).matches()) {
            return new Reply(400, new JSONObject().put("error", "bad_request"));
        }

        // Mock-passport proofs are only accepted when the bot itself opts in (test servers).
        if (body.optBoolean("devMode", false) && !"true".equalsIgnoreCase(System.getenv("ZK_ALLOW_DEV_PROOFS"))) {
            System.out.println("⚠️ Rejected a dev-mode (mock passport) age proof: ZK_ALLOW_DEV_PROOFS is not enabled on the bot.");
            return new Reply(403, new JSONObject().put("status", "dev_proof_not_allowed"));
        }

        // Banned accounts, and ID fingerprints that belong to a banned account, can never verify.
        DatabaseManager db = DatabaseManager.getInstance();
        if (db.isAgeBanned(discordUserId) || db.isProofIdBanned(idFingerprint)) {
            System.out.println("🚫 Age proof refused: account or ID is on the age-verification ban list (requested by " + discordUserId + ")");
            return new Reply(403, new JSONObject().put("status", "banned"));
        }

        DatabaseManager.AgeProofOutcome outcome = db.completeProofVerification(sha256Hex(token), idFingerprint, discordUserId);

        switch (outcome.status()) {
            case OK:
                System.out.println("✦ Zero-knowledge age proof accepted for user ID: " + outcome.userId());
                grantAdultRole(jda, outcome.userId());
                return new Reply(200, new JSONObject().put("status", "ok"));
            case DUPLICATE_ID:
                System.out.println("⚠️ Age proof rejected: ID already linked to another account (requested by " + outcome.userId() + ")");
                return new Reply(409, new JSONObject().put("status", "duplicate_id"));
            case INVALID_SESSION:
                return new Reply(410, new JSONObject().put("status", "invalid_session"));
            default:
                return new Reply(500, new JSONObject().put("status", "error"));
        }
    }

    /**
     * Swaps the minor role for the adult role in every guild the user is a member of, then sends one
     * confirmation DM (best effort: closed DMs are ignored).
     */
    private static void grantAdultRole(JDA jda, String userId) {
        String adultRoleId = System.getenv("ADULT_ROLE_ID");
        if (jda == null || adultRoleId == null || adultRoleId.isBlank()) {
            return;
        }
        String minorRoleId = System.getenv("MINOR_ROLE_ID");
        AtomicBoolean confirmationSent = new AtomicBoolean(false);
        for (Guild guild : jda.getGuilds()) {
            Role adultRole = guild.getRoleById(adultRoleId);
            if (adultRole == null) {
                continue;
            }
            Role minorRole = (minorRoleId != null && !minorRoleId.isBlank()) ? guild.getRoleById(minorRoleId) : null;
            guild.retrieveMemberById(userId).queue(
                member -> {
                    if (minorRole != null && member.getRoles().contains(minorRole)) {
                        guild.removeRoleFromMember(member, minorRole).queue();
                    }
                    guild.addRoleToMember(member, adultRole).queue(
                        s -> {
                            System.out.println("✦ Assigned 18+ role to " + member.getUser().getName());
                            if (confirmationSent.compareAndSet(false, true)) {
                                sendConfirmationDm(member.getUser(), guild, adultRole);
                            }
                        },
                        err -> System.err.println("❌ Failed to assign 18+ role: " + err.getMessage())
                    );
                },
                failure -> { } // user is not in this guild
            );
        }
    }

    private static void sendConfirmationDm(User user, Guild guild, Role adultRole) {
        String adultChannelId = System.getenv("ADULT_CHANNEL_ID");
        String unlocked = adultRole.getAsMention()
                + ((adultChannelId != null && !adultChannelId.isBlank()) ? "\n<#" + adultChannelId + ">" : "");

        EmbedBuilder embed = new EmbedBuilder()
            .setColor(Color.decode("#FF5FA2"))
            .setTitle("AGE PROOF ACCEPTED: 18+ ZONE UNLOCKED")
            .setDescription("Your zero-knowledge proof checked out. **" + guild.getName() + "** now treats you as 18+.")
            .addField("What was proven", "That you are **18 or older**. Nothing else was requested.", false)
            .addField("What AMORA never received", "Your name, birthdate, nationality, document number or photo.", false)
            .addField("What AMORA stored", "Your Discord ID, a verified flag, and a one-way fingerprint of the proof ID "
                    + "(it only stops one document from unlocking several accounts).", false)
            .addField("Unlocked", unlocked, false)
            .addField("Receipt", "Method: **zkPassport zero-knowledge proof**\nVerified: <t:" + Instant.now().getEpochSecond() + ":F> (<t:"
                    + Instant.now().getEpochSecond() + ":R>)", false)
            .setFooter("AMORA Safety • Questions or a mistake? Contact staff.", null);

        user.openPrivateChannel()
            .flatMap(channel -> channel.sendMessageEmbeds(embed.build()))
            .queue(
                ok -> System.out.println("✦ Sent age-proof confirmation DM to " + user.getName()),
                err -> System.out.println("ℹ️ Could not DM confirmation to " + user.getName() + " (DMs closed?): " + err.getMessage())
            );
    }

    private static final class SignedJsonHandler implements HttpHandler {
        private final JsonEndpoint endpoint;

        SignedJsonHandler(JsonEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String secret = sharedSecret();
            if (secret == null) {
                respond(exchange, 503, new JSONObject().put("error", "disabled"));
                return;
            }

            byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (raw.length > MAX_BODY_BYTES) {
                respond(exchange, 413, new JSONObject().put("error", "too_large"));
                return;
            }
            String body = new String(raw, StandardCharsets.UTF_8);

            String timestamp = exchange.getRequestHeaders().getFirst("X-Amora-Timestamp");
            String signature = exchange.getRequestHeaders().getFirst("X-Amora-Signature");
            if (!isAuthentic(secret, timestamp, body, signature, System.currentTimeMillis())) {
                respond(exchange, 401, new JSONObject().put("error", "unauthorized"));
                return;
            }

            JSONObject json;
            try {
                json = new JSONObject(body);
            } catch (JSONException e) {
                respond(exchange, 400, new JSONObject().put("error", "bad_json"));
                return;
            }

            Reply reply = endpoint.handle(json);
            respond(exchange, reply.status(), reply.body());
        }
    }

    static boolean isAuthentic(String secret, String timestamp, String body, String signatureHex, long nowMillis) {
        if (timestamp == null || signatureHex == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowMillis - ts) > MAX_CLOCK_SKEW_MS) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmacSha256(secret, timestamp + "." + body), provided);
    }

    static byte[] hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sharedSecret() {
        String secret = System.getenv("VERIFIER_SHARED_SECRET");
        return (secret != null && secret.length() >= MIN_SECRET_LENGTH) ? secret : null;
    }

    private static void respond(HttpExchange exchange, int status, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
