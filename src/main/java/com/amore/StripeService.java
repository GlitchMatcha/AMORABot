package com.amore;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionCreateParams;

public class StripeService {

    static {
        String apiKey = System.getenv("STRIPE_SECRET_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            Stripe.apiKey = apiKey;
        } else {
            System.err.println(" Warning: STRIPE_SECRET_KEY environment variable is missing!");
        }
    }

    public static String createVerificationSession(String discordUserId) throws StripeException {
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