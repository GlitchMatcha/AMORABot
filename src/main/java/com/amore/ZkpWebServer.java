package com.amore;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class ZkpWebServer {
    public static void startServer(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/verify", new VerifyHandler());
            server.setExecutor(null); 
            server.start();
            System.out.println("✦ ZKP Embedded Web Server started on port " + port);
        } catch (IOException e) {
            System.out.println(" Failed to start ZKP web server: " + e.getMessage());
        }
    }

    static class VerifyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String sessionId = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1 && pair[0].equals("session")) {
                        sessionId = pair[1];
                    }
                }
            }

            String responseHtml;
            DatabaseManager db = DatabaseManager.getInstance();

            if (sessionId != null) {
                String userId = db.verifyZkpSession(sessionId);
                if (userId != null) {
                    db.markUserVerified(userId);
                    
                    responseHtml = "<html><body style='background:#121212;color:#ffffff;font-family:sans-serif;text-align:center;padding-top:80px;'>"
                             + "<h1 style='color:#FF5FA2;'> Verification Successful!</h1>"
                             + "<p>Your cryptographic age check has been validated. You can close this window and click the <b>18+ Adult Zone</b> button back in Discord!</p>"
                             + "</body></html>";
                } else {
                    responseHtml = "<html><body style='background:#121212;color:#ff6b6b;font-family:sans-serif;text-align:center;padding-top:80px;'>"
                             + "<h1>❌ Invalid or Expired Session</h1>"
                             + "<p>This verification link has expired or is invalid. Please go back to Discord and click the button again.</p>"
                             + "</body></html>";
                }
            } else {
                responseHtml = "<html><body style='background:#121212;color:#ff6b6b;font-family:sans-serif;text-align:center;padding-top:80px;'>"
                         + "<h1> Missing Session</h1><p>No verification session was provided.</p></body></html>";
            }

            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseHtml.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseHtml.getBytes());
            os.close();
        }
    }
}