package dev.youneskaouani.vestige.ingestion.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.common.config.VestigeProperties;
import dev.youneskaouani.vestige.common.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects an oversized SARIF upload with {@code 413} before a single byte of the body is read
 * (§4.3).
 *
 * <p>This has to run as a servlet filter, not a check inside {@code RunController}: by the time a
 * {@code @RequestBody byte[]} parameter is bound, Spring has already buffered the whole body into
 * memory, which is exactly the cost §4.3 asks to avoid for a report that is hundreds of megabytes
 * oversized. A filter can refuse the request from the declared {@code Content-Length} alone.
 *
 * <p>This is a best-effort check, not a guarantee: a chunked request with no {@code Content-Length}
 * sails past it, and {@code RunIngestionService} re-checks the buffered array's actual length as a
 * second line of defence for exactly that case - documented there, not silently assumed.
 */
@Component
@Order(0)
public class ReportSizeFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/v1/runs";

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public ReportSizeFilter(VestigeProperties properties, ObjectMapper objectMapper) {
        this.maxBytes = properties.ingestion().maxReportBytes().toBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(PATH.equals(request.getRequestURI())
                && "POST".equalsIgnoreCase(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBytes) {
            writeTooLarge(request, response, declaredLength);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeTooLarge(
            HttpServletRequest request, HttpServletResponse response, long declaredLength)
            throws IOException {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Report is %d bytes, exceeding the %d byte limit"
                                .formatted(declaredLength, maxBytes));
        problem.setType(URI.create(Problems.BASE_URI + "payload-too-large"));
        problem.setTitle(HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
