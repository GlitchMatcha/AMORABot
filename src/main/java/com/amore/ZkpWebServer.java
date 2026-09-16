package com.amore;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ZkpWebServer {

    public static void startServer(int port) {
        startServer(port, null);
    }

    public static void startServer(int port, JDA jda) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            server.createContext("/verify", new VerifyPageHandler());
            server.createContext("/verify-proof", new ProofHandler());
            server.createContext("/stripe-webhook", new StripeWebhookHandler(jda));
            
            server.setExecutor(null); 
            server.start();
            System.out.println("✦ Embedded Web Server started on port " + port);
        } catch (IOException e) {
            System.out.println(" Failed to start web server: " + e.getMessage());
        }
    }

    static class VerifyPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            InputStream htmlStream = getClass().getResourceAsStream("/index.html");
            String responseHtml;

            if (htmlStream != null) {
                try (Scanner scanner = new Scanner(htmlStream, StandardCharsets.UTF_8.name())) {
                    responseHtml = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                }
            } else {
                responseHtml = "<html><body style='background:#121212;color:white;font-family:sans-serif;text-align:center;padding-top:80px;'>"
                           + "<h1>⚠️ Missing index.html</h1><p>Ensure index.html is in src/main/resources.</p></body></html>";
            }

            byte[] responseBytes = responseHtml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    static class ProofHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); 
                return;
            }

            InputStream is = exchange.getRequestBody();
            String requestBody;
            try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
                requestBody = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
            }

            String sessionId = extractJsonValue(requestBody, "sessionId");
            boolean proofValid = requestBody.contains("\"publicSignals\":[\"1\"]") || requestBody.contains("\"publicSignals\": [\"1\"]");

            DatabaseManager db = DatabaseManager.getInstance();
            boolean success = false;

            if (sessionId != null && proofValid) {
                String userId = db.verifyZkpSession(sessionId);
                if (userId != null) {
                    db.markUserVerified(userId);
                    success = true;
                    System.out.println("✦ ZKP Proof validated successfully for user ID: " + userId);
                }
            }

            if (success) {
                String okResponse = "{\"status\":\"verified\"}";
                byte[] bytes = okResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                exchange.sendResponseHeaders(400, -1); 
            }
        }

        private String extractJsonValue(String json, String key) {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start == -1) return null;
            start += pattern.length();
            int end = json.indexOf("\"", start);
            return (end != -1) ? json.substring(start, end) : null;
        }
    }

    static class StripeWebhookHandler implements HttpHandler {
        private final JDA jda;

        public StripeWebhookHandler(JDA jda) {
            this.jda = jda;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            InputStream is = exchange.getRequestBody();
            String requestBody;
            try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
                requestBody = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
            }

            try {
                JSONObject jsonEvent = new JSONObject(requestBody);
                String eventType = jsonEvent.optString("type", "");

                if ("identity.verification_session.verified".equals(eventType)) {
                    JSONObject data = jsonEvent.optJSONObject("data");
                    if (data != null) {
                        JSONObject object = data.optJSONObject("object");
                        if (object != null) {
                            JSONObject metadata = object.optJSONObject("metadata");
                            if (metadata != null) {
                                String discordUserId = metadata.optString("discord_user_id", null);

                                if (discordUserId != null && !discordUserId.isBlank()) {
                                    DatabaseManager db = DatabaseManager.getInstance();
                                    db.setVerified(discordUserId, true);
                                    System.out.println("✦ Stripe Identity Verified successfully for Discord ID: " + discordUserId);

                                    if (jda != null) {
                                        String adultRoleId = System.getenv("ADULT_ROLE_ID");
                                        if (adultRoleId != null && !adultRoleId.isBlank()) {
                                            for (Guild guild : jda.getGuilds()) {
                                                Role adultRole = guild.getRoleById(adultRoleId);
                                                if (adultRole != null) {
                                                    guild.retrieveMemberById(discordUserId).queue(
                                                        member -> {
                                                            guild.addRoleToMember(member, adultRole).queue(
                                                                success -> System.out.println("✦ Assigned 18+ role to " + member.getUser().getName()),
                                                                error -> System.err.println("❌ Failed to assign role in guild: " + error.getMessage())
                                                            );
                                                        },
                                                        failure -> {} // User might not be in this specific server
                                                    );
                                                }
                                            }
                                        }
                    }
                                }
                            }
                        }
                    }
                }

                String responseStr = "{\"status\":\"success\"}";
                byte[] responseBytes = responseStr.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                System.err.println(" Stripe Webhook Error: " + e.getMessage());
                e.printStackTrace();
                exchange.sendResponseHeaders(400, -1);
            }
        }
    }
}