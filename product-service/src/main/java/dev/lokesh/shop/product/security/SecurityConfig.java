package dev.lokesh.shop.product.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security. Browsing the catalog is public, changing it needs a signed-in
 * user, and inventory (reserve / commit / release, step 5) is for internal service tokens only.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemAuthHandlers problems) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // no cookies or sessions, so no CSRF surface
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .requestMatchers("/api/inventory/**").hasAuthority(Tokens.INTERNAL_AUTHORITY)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys) {
        return Tokens.decoder(keys);
    }
}
