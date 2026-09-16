package com.amore;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionCreateParams;

public class StripeService {

    static {
        initializeStripeKey();
    }

    private static void initializeStripeKey() {
        String apiKey = System.getenv("STRIPE_SECRET_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("STRIPE_API_KEY");
        }
        
        if (apiKey != null && !apiKey.isBlank()) {
            Stripe.apiKey = apiKey;
            System.out.println("✦ Stripe API Key loaded successfully.");
        } else {
            System.err.println(" CRITICAL ERROR: Neither STRIPE_SECRET_KEY nor STRIPE_API_KEY environment variable is set in Render!");
        }
    }

    public static String createVerificationSession(String discordUserId) throws StripeException {
        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            initializeStripeKey();
        }

        VerificationSessionCreateParams params = VerificationSessionCreateParams.builder()
                .setType(VerificationSessionCreateParams.Type.DOCUMENT)
                .putMetadata("discord_user_id", discordUserId)
                .setOptions(
                        VerificationSessionCreateParams.Options.builder()
                                .setDocument(
                                        VerificationSessionCreateParams.Options.Document.builder()
                                                .setRequireMatchingSelfie(true)
                                                .build()
                                )
                                .build()
                )
                .build();

        VerificationSession session = VerificationSession.create(params);
        return session.getUrl();
    }
}