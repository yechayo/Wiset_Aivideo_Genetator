package com.comic.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（基于 jjwt 0.11.5，兼容 Java 8）
 *
 * 双 Token 方案：
 *   AccessToken  - 有效期短（2h），用于接口认证
 *   RefreshToken - 有效期长（7d），仅用于换取新 AccessToken
 *
 * 注意：0.11.5 和 0.12.x API 差异较大
 *   0.11.5: Jwts.parserBuilder()  / .setSubject() / .claim() 返回 Claims
 *   0.12.x: Jwts.parser()        / .subject()
 */
@Component
public class JwtUtil {

    @Value("${comic.jwt.secret}")
    private String secret;

    @Value("${comic.jwt.access-token-expiration}")
    private long accessExpiration;

    @Value("${comic.jwt.refresh-token-expiration}")
    private long refreshExpiration;

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_TYPE    = "type";
    private static final String TYPE_ACCESS   = "access";
    private static final String TYPE_REFRESH  = "refresh";

    // ──────────── 生成 ────────────

    public String generateAccessToken(String username, Long userId) {
        return buildToken(username, userId, TYPE_ACCESS, accessExpiration);
    }

    public String generateRefreshToken(String username, Long userId) {
        return buildToken(username, userId, TYPE_REFRESH, refreshExpiration);
    }

    private String buildToken(String username, Long userId, String type, long expiration) {
        return Jwts.builder()
                .setSubject(username)                          // 0.11.5 用 setSubject
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_TYPE, type)
                .setIssuedAt(new Date())                       // 0.11.5 用 setIssuedAt
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    // ──────────── 解析 ────────────

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public Long extractUserId(String token) {
        return parseClaims(token).get(CLAIM_USER_ID, Long.class);
    }

    public Date extractExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    public boolean isAccessToken(String token) {
        return TYPE_ACCESS.equals(parseClaims(token).get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(parseClaims(token).get(CLAIM_TYPE, String.class));
    }

    // ──────────── 校验 ────────────

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // ──────────── 私有 ────────────

    private Claims parseClaims(String token) {
        // 0.11.5 用 parserBuilder()，0.12.x 改成了 parser()
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
