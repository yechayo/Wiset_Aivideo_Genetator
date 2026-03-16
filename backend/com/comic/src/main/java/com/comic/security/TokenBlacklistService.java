package com.comic.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单服务（退出登录时将 Token 加入黑名单使其立即失效）
 *
 * 现在用 Caffeine 内存缓存，上服务器换 Redis 只需替换这一个类的实现。
 *
 * 换 Redis 时的替换代码见注释底部。
 */
@Component
public class TokenBlacklistService {

    private final Cache<String, Byte> blacklist;

    public TokenBlacklistService(@Value("${comic.blacklist.max-size:10000}") long maxSize) {
        this.blacklist = Caffeine.newBuilder()
                .maximumSize(maxSize)
                // Token 自然过期后自动从黑名单移除，避免内存无限增长
                .expireAfterWrite(7, TimeUnit.DAYS)
                .build();
    }

    /**
     * 加入黑名单
     * @param token      要注销的 Token 字符串
     * @param expiration Token 的过期时间（JWT 里解析出来的）
     */
    public void add(String token, Date expiration) {
        long ttlMillis = expiration.getTime() - System.currentTimeMillis();
        if (ttlMillis > 0) {
            blacklist.put(token, (byte) 1);
        }
        // ttl <= 0 说明 Token 已过期，不需要加黑名单
    }

    /** 判断 Token 是否已被注销 */
    public boolean isBlacklisted(String token) {
        return blacklist.getIfPresent(token) != null;
    }
}

/*
 * ──── 上服务器后换 Redis 的完整替换代码 ────
 *
 * @Component
 * @RequiredArgsConstructor
 * public class TokenBlacklistService {
 *
 *     private final RedisTemplate<String, String> redis;
 *     private static final String PREFIX = "token:blacklist:";
 *
 *     public void add(String token, Date expiration) {
 *         long ttlSeconds = (expiration.getTime() - System.currentTimeMillis()) / 1000;
 *         if (ttlSeconds > 0) {
 *             redis.opsForValue().set(PREFIX + token, "1", ttlSeconds, TimeUnit.SECONDS);
 *         }
 *     }
 *
 *     public boolean isBlacklisted(String token) {
 *         return Boolean.TRUE.equals(redis.hasKey(PREFIX + token));
 *     }
 * }
 */
