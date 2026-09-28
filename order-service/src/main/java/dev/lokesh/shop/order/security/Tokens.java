package dev.lokesh.shop.order.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Set;

/** Token vocabulary shared by the security config, the token issuer and ownership checks. */
public final class Tokens {

    /** user-service signs user tokens; order-service signs short-lived service tokens. */
    public static final String USER_ISSUER = "shop-user-service";
    public static final String SERVICE_ISSUER = "shop-order-service";
    public static final Set<String> TRUSTED_ISSUERS = Set.of(USER_ISSUER, SERVICE_ISSUER);

    public static final String USER_SCOPE = "user";
    public static final String INTERNAL_SCOPE = "internal";
    public static final String INTERNAL_AUTHORITY = "SCOPE_" + INTERNAL_SCOPE;

    private Tokens() {
    }

    /** HS256 only, a trusted issuer, and the default expiry / not-before checks. */
    public static JwtDecoder decoder(JwtKeys keys) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(keys.key()).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> issuer = new JwtClaimValidator<String>(JwtClaimNames.ISS, TRUSTED_ISSUERS::contains);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), issuer));
        return decoder;
    }

    public static boolean isInternal(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        return scope != null && Set.of(scope.split(" ")).contains(INTERNAL_SCOPE);
    }
}
