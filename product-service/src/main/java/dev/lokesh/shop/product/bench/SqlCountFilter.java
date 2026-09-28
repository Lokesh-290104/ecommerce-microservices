package dev.lokesh.shop.product.bench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * Benchmark only: adds X-SQL-Count (statements this request ran) to every response. The body
 * is buffered so the header can be set after the controller finished.
 */
@Component
@Profile("bench")
public class SqlCountFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        SqlCounter.reset();
        try {
            chain.doFilter(request, buffered);
        } finally {
            buffered.setHeader("X-SQL-Count", Integer.toString(SqlCounter.count()));
            buffered.copyBodyToResponse();
        }
    }
}
