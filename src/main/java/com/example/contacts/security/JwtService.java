package com.example.contacts.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Issues and validates stateless HS256 JWTs used as bearer tokens.
 *
 * <p>The signing secret and token lifetime are configured via
 * {@code app.jwt.secret} / {@code app.jwt.expiration-ms}. The secret must be at
 * least 256 bits (32 bytes) for HS256.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * Builds a signed token for the given username, embedding the role as a claim.
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * Extracts the username (subject) from a valid token.
     *
     * @throws JwtException if the token is malformed, expired or has a bad signature
     */
    public String extractUsername(String token) {
        return parse(token).getSubject();
    }

    /**
     * Returns {@code true} if the token is well-formed, correctly signed and not expired.
     */
    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    /** Token lifetime in milliseconds (exposed so the API can report expiry to clients). */
    public long getExpirationMs() {
        return expirationMs;
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
