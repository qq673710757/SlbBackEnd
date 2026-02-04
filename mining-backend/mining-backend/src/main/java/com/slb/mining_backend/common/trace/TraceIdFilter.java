package com.slb.mining_backend.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Ensures every request and response carries an {@code X-Trace-Id} for correlation.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".TRACE_ID";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String supplied = request.getHeader(TraceIdHolder.TRACE_ID_HEADER);
        String traceId = StringUtils.hasText(supplied) ? supplied.trim() : generateTraceId();

        TraceIdHolder.set(traceId);
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        response.setHeader(TraceIdHolder.TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            response.setHeader(TraceIdHolder.TRACE_ID_HEADER, traceId);
            TraceIdHolder.clear();
        }
    }

    public static String getTraceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(REQUEST_ATTRIBUTE);
        if (attribute instanceof String id && StringUtils.hasText(id)) {
            return id;
        }
        return TraceIdHolder.require();
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
