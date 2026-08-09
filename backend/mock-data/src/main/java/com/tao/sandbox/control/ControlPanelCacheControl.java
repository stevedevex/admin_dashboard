package com.tao.sandbox.control;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stops the browser reusing control-panel answers.
 *
 * <p>Left without a directive, a browser applies <em>heuristic freshness</em> to an ETagged
 * response and serves a stored copy without asking. The dashboard then shows a mock as {@code
 * unchecked} seconds after validating it, disagrees with the tree beside it, and nothing on screen
 * explains why — the request that would have corrected it was never sent.
 *
 * <p>{@code no-store} rather than {@code no-cache}, because the milder directive is not enough
 * here: it permits a conditional request, and this ETag would answer it wrongly. The ETag covers
 * the payload and its sidecars — the things {@code If-Match} protects from a concurrent
 * overwrite — while the representation also carries a mock's validation verdict, which changes
 * when someone validates and the content does not. A 304 is then perfectly correct by the ETag's
 * own terms and still replays a stale verdict.
 *
 * <p>So the ETag is what it always was, an optimistic-concurrency token, and this header stops
 * HTTP caching from treating it as a freshness validator too. The cost is nil: these payloads are
 * small, local, and read by one dashboard.
 *
 * <p>Scoped to {@code /__tao} on purpose. The data plane imitates someone else's service, and its
 * caching behaviour is part of what the application under test is entitled to see — inventing
 * headers there would make the sandbox differ from the upstream it stands in for.
 */
@Component
public class ControlPanelCacheControl extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/__tao");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        chain.doFilter(request, response);
    }
}
