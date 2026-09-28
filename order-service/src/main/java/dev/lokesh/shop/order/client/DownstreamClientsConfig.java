package dev.lokesh.shop.order.client;

import dev.lokesh.shop.order.security.ServiceTokenIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * One RestClient per downstream service, each with its own connect/read timeouts (design:
 * Resilience table). Every request carries a fresh 60-second service token; a user's own token
 * is never forwarded, so users can't reach internal endpoints through order-service.
 */
@Configuration
public class DownstreamClientsConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    @Bean
    RestClient userRestClient(@Value("${shop.services.user-url}") String baseUrl, ServiceTokenIssuer tokens) {
        return client(baseUrl, Duration.ofSeconds(1), tokens);
    }

    @Bean
    RestClient productRestClient(@Value("${shop.services.product-url}") String baseUrl, ServiceTokenIssuer tokens) {
        return client(baseUrl, Duration.ofSeconds(2), tokens);
    }

    @Bean
    RestClient paymentRestClient(@Value("${shop.services.payment-url}") String baseUrl, ServiceTokenIssuer tokens) {
        return client(baseUrl, Duration.ofSeconds(2), tokens);
    }

    private static RestClient client(String baseUrl, Duration readTimeout, ServiceTokenIssuer tokens) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(readTimeout);
        ClientHttpRequestInterceptor serviceToken = (request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue());
            return execution.execute(request, body);
        };
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).requestInterceptor(serviceToken).build();
    }
}
