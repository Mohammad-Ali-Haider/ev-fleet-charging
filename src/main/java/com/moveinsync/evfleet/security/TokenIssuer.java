package com.moveinsync.evfleet.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Local demo CLI only: deliberately no public unauthenticated issuing endpoint.
 */
public final class TokenIssuer {

    /** Creates a signed JWT for local testing and demos. */
    public static String issue(String key, String tenant, String role, String subject, long ttlSeconds) {
        if (key == null || key.length() < 32) {
            throw new IllegalArgumentException("EV_JWT_SECRET required");
        }

        try {
            long now = System.currentTimeMillis();
            var claims = new JWTClaimsSet.Builder().issuer("evfleet").audience("evfleet-api").subject(subject).claim("tenant", tenant).claim("role",
                role).issueTime(new Date(now)).expirationTime(new Date(now + ttlSeconds * 1000)).build();
            var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            token.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));

            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("TOKEN_ISSUE_FAILED");
        }
    }

    /** Command-line entry: prints a token for a tenant, role and user. */
    public static void main(String[] args) {
        System.out.println(issue(System.getenv("EV_JWT_SECRET"), args.length > 0 ? args[0] : "demo", args.length > 1 ? args[1] : "admin",
            args.length > 2 ? args[2] : "demo-admin", 3600));
    }
}
