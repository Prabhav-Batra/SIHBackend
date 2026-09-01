package com.sih26046.ctms.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Issues and verifies access tokens (§18.2).
 *
 * <p>Signing and verification are delegated to Spring Security's Nimbus integration rather
 * than hand-rolled, which is what supplies signature checking, {@code exp} validation and
 * clock-skew handling without this class implementing any of them.
 *
 * <p>The {@link Clock} is a constructor parameter so token lifetime is testable without
 * waiting fifteen minutes.
 */
public class AccessTokenService {

    private static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;
    private static final String CLAIM_SESSION_ID = "sid";
    private static final String CLAIM_ROLE = "role";

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    public AccessTokenService(String secret, Duration ttl, Clock clock) {
        SecretKeySpec key =
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM.getName());
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(ALGORITHM).build();
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Issues a signed access token for one session. */
    public String issue(UUID userId, UUID sessionId, String roleName) {
        Instant now = clock.instant();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject(userId.toString())
                        .id(UUID.randomUUID().toString())
                        .issuedAt(now)
                        .expiresAt(now.plus(ttl))
                        .claim(CLAIM_SESSION_ID, sessionId.toString())
                        .claim(CLAIM_ROLE, roleName)
                        .build();

        return encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(ALGORITHM).build(), claims))
                .getTokenValue();
    }

    /**
     * Verifies signature and expiry, returning the claims.
     *
     * @throws org.springframework.security.oauth2.jwt.JwtException if the token is forged,
     *     tampered with, or expired
     */
    public AccessTokenClaims verify(String token) {
        Jwt jwt = decoder.decode(token);
        return new AccessTokenClaims(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(CLAIM_SESSION_ID)),
                jwt.getClaimAsString(CLAIM_ROLE),
                UUID.fromString(jwt.getId()));
    }
}
