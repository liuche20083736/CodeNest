package com.opencodeweb.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

@Service
public class JwtService {

    private static final Duration TTL = Duration.ofDays(7);

    private final SecretKey key;

    public JwtService(@Value("${ocw.jwt-secret:}") String configured) {
        byte[] bytes;
        if (configured != null && !configured.isBlank()) {
            byte[] raw = configured.getBytes(StandardCharsets.UTF_8);
            bytes = new byte[Math.max(32, raw.length)];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = raw[i % raw.length];
            }
        } else {
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String issue(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TTL.toMillis()))
                .signWith(key)
                .compact();
    }

    /** Returns null if the token is missing/invalid. */
    public String validate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
