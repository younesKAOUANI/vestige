package dev.youneskaouani.vestige.tenancy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.tenancy.service.ApiKeyAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code X-API-Key} and publishes the resulting tenant for the rest of the request.
 *
 * <p>Written as a plain servlet filter rather than pulling in Spring Security: the scheme is a
 * single header resolved to a single principal, and the whole of it is visible in this one class.
 * Spring Security would bring a filter chain, a context holder and an authentication manager to
 * express the same twenty lines, and every one of those is a place for a misconfiguration to hide.
 * The trade-off, and the point at which it stops being the right one, is recorded in ADR 0008.
 *
 * <p>The rejection is written here rather than thrown: an exception escaping a filter never reaches
 * {@code @RestControllerAdvice}, so throwing would produce a container error page instead of the
 * {@code application/problem+json} the rest of the API promises.
 *
 * <p>The context is cleared in a {@code finally} block. Servlet containers reuse threads, and a
 * leaked tenant would be worse than having no isolation at all.
 */
@Component
@Order(1)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    /**
     * Paths that are not tenant-scoped. Webhooks authenticate with an HMAC over the body instead,
     * and the docs and health endpoints are deliberately open.
     */
    private static final List<String> UNAUTHENTICATED_PREFIXES =
            List.of("/webhooks/", "/actuator/health", "/v3/api-docs", "/swagger-ui", "/error");

    private final ApiKeyAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator, ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return UNAUTHENTICATED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<UUID> organizationId = authenticator.authenticate(request.getHeader(HEADER));
        if (organizationId.isEmpty()) {
            writeUnauthorized(request, response);
            return;
        }
        try {
            TenantContext.set(organizationId.get());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "A valid " + HEADER + " header is required");
        problem.setType(URI.create(Problems.BASE_URI + "unauthorized"));
        problem.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
