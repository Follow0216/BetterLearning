package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTests {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesServerTraceIdEvenWhenHeaderProvided() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdContext.HEADER, "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] traceIdInChain = new String[1];
        FilterChain chain = (servletRequest, servletResponse) ->
                traceIdInChain[0] = MDC.get(TraceIdContext.MDC_KEY);

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(TraceIdContext.HEADER);
        assertThat(traceId).hasSize(32).isNotEqualTo("req-123");
        assertThat(request.getAttribute(TraceIdContext.REQUEST_ATTRIBUTE)).isEqualTo(traceId);
        assertThat(traceIdInChain[0]).isEqualTo(traceId);
        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isNull();
    }

    @Test
    void generatesServerTraceIdInsteadOfUnsafeHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdContext.HEADER, "bad\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        String traceId = response.getHeader(TraceIdContext.HEADER);
        assertThat(traceId).hasSize(32).isNotEqualTo("bad\nvalue");
        assertThat(request.getAttribute(TraceIdContext.REQUEST_ATTRIBUTE)).isEqualTo(traceId);
        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isNull();
    }

    @Test
    void generatesTraceIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertThat(response.getHeader(TraceIdContext.HEADER)).hasSize(32);
        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcWhenDownstreamThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isNull();
    }

    @Test
    void restoresPreviousMdcValue() throws Exception {
        MDC.put(TraceIdContext.MDC_KEY, "outer");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isEqualTo("outer");
    }
}
