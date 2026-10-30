package com.moveinsync.evfleet.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Login and access rules: JWT tokens, roles, and which URLs are public. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Checks token signature, expiry, issuer, audience, role and tenant. */
    @Bean
    JwtDecoder decoder() {
        String key = System.getenv("EV_JWT_SECRET");

        if (key == null || key.length() < 32) {
            throw new IllegalStateException("EV_JWT_SECRET must contain at least 32 characters");
        }

        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).macAlgorithm(
            MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            String role = jwt.getClaimAsString("role");
            String tenant = jwt.getClaimAsString("tenant");
            String subject = jwt.getSubject();
            boolean ok = jwt.getAudience().contains("evfleet-api") && Set.of("admin", "operator", "employee").contains(role == null ? "" : role)
                && tenant != null && !tenant.isBlank() && tenant.length() <= 80 && subject != null && !subject.isBlank() && subject.length() <= 80
                && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null;

            return ok ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("evfleet"), claims));

        return decoder;
    }

    /** URL rules: public pages, metrics for admins and operators, everything else needs a token. */
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(
            a -> a.requestMatchers("/health", "/actuator/health", "/demo", "/index.html", "/timeline.css", "/timeline.js", "/docs", "/docs/**",
                "/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll().requestMatchers("/actuator/prometheus").hasAnyRole("admin",
                    "operator").anyRequest().authenticated()).oauth2ResourceServer(
                        o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint((r, s, e) -> {
                            s.setStatus(401);
                            s.setContentType("application/json");
                            s.getWriter().write("{\"error\":{\"code\":\"INVALID_TOKEN\"}}");
                        })).exceptionHandling(e -> e.authenticationEntryPoint((r, s, x) -> {
                            s.setStatus(401);
                            s.setContentType("application/json");
                            s.getWriter().write("{\"error\":{\"code\":\"AUTH_REQUIRED\"}}");
                        }).accessDeniedHandler((r, s, x) -> {
                            s.setStatus(403);
                            s.setContentType("application/json");
                            s.getWriter().write("{\"error\":{\"code\":\"FORBIDDEN\"}}");
                        }));

        return http.build();
    }
}
