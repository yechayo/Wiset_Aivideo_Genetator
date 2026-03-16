package com.comic.security;

import javax.servlet.*;
import javax.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 请求拦截器
 * 每次请求都会经过这里，负责从 Header 解析 Token 并设置认证信息
 *
 * 拦截逻辑：
 *   1. 从 Authorization: Bearer <token> 中提取 Token
 *   2. 验证 Token 合法性（签名、过期时间）
 *   3. 检查是否在黑名单中（已退出登录）
 *   4. 确认是 AccessToken 类型（拒绝用 RefreshToken 访问接口）
 *   5. 通过则把用户信息写入 SecurityContext，后续接口可直接取用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil               jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final TokenBlacklistService  blacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token)) {
            if (jwtUtil.validateToken(token)
                    && !blacklistService.isBlacklisted(token)
                    && jwtUtil.isAccessToken(token)) {

                String username = jwtUtil.extractUsername(token);
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());

                SecurityContextHolder.getContext().setAuthentication(auth);

            } else {
                log.debug("Token 无效或已注销: {}", request.getRequestURI());
            }
        }

        chain.doFilter(request, response);
    }

    /** 从 Header 提取 Token："Authorization: Bearer xxxxx" → "xxxxx" */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
