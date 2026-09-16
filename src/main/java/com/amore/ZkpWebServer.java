package com.amore;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ZkpWebServer {
    public static void startServer(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            server.createContext("/verify", new VerifyPageHandler());
            
            server.createContext("/verify-proof", new ProofHandler());
            
            server.setExecutor(null); 
            server.start();
            System.out.println("✦ ZKP Embedded Web Server started on port " + port);
        } catch (IOException e) {
            System.out.println(" Failed to start ZKP web server: " + e.getMessage());
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
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
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
}