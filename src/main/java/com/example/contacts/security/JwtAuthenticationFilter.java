package com.example.contacts.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates the {@code Authorization: Bearer <jwt>} header on each request and,
 * when valid, populates the {@link SecurityContextHolder} with the authenticated
 * user. Requests without a bearer token (or with an existing authentication —
 * e.g. test {@code @WithMockUser}) pass through untouched so the authorization
 * rules in {@code SecurityConfig} decide the outcome.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, CustomUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        if (jwtService.isValid(token)) {
            String username = jwtService.extractUsername(token);
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                // Reject tokens for accounts disabled after the token was issued.
                if (userDetails.isEnabled()) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (UsernameNotFoundException ex) {
                // The account was deleted after the token was issued. Pass
                // through unauthenticated so the request gets a clean 401
                // (instead of a 500 from the uncaught lookup failure).
            }
        }

        filterChain.doFilter(request, response);
    }
}
