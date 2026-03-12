package com.comic.service;

import com.comic.dto.*;
import com.comic.entity.User;
import com.comic.repository.UserRepository;
import com.comic.security.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证业务逻辑
 *
 * 流程说明：
 *   注册 → 检查用户名唯一 → BCrypt 加密密码 → 存库 → 签发双 Token
 *   登录 → Spring Security 验证用户名密码 → 签发双 Token
 *   退出 → AccessToken 加入黑名单（立即失效）
 *   刷新 → 验证 RefreshToken → 签发新 AccessToken
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository        userRepository;
    private final PasswordEncoder       passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil               jwtUtil;
    private final TokenBlacklistService blacklistService;

    // ──────────────────────────────────────────
    //  注册
    // ──────────────────────────────────────────
    public AuthResponse register(RegisterRequest req) {
        // 1. 检查用户名是否已存在
        if (userRepository.countByUsername(req.getUsername()) > 0) {
            throw new RuntimeException("用户名已存在");
        }

        // 2. 构建并保存用户（密码 BCrypt 加密）
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setEmail(req.getEmail());
        userRepository.insert(user);

        log.info("新用户注册成功: {}", user.getUsername());

        // 3. 注册成功直接返回 Token，前端免二次登录
        return buildAuthResponse(user);
    }

    // ──────────────────────────────────────────
    //  登录
    // ──────────────────────────────────────────
    public AuthResponse login(LoginRequest req) {
        // Spring Security 内部会调用 UserDetailsServiceImpl.loadUserByUsername
        // 然后用 BCrypt 比对密码，失败则抛出 BadCredentialsException
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            req.getUsername(), req.getPassword())
            );
        } catch (BadCredentialsException e) {
            throw new RuntimeException("用户名或密码错误");
        }

        User user = userRepository.findByUsername(req.getUsername());
        log.info("用户登录成功: {}", user.getUsername());
        return buildAuthResponse(user);
    }

    // ──────────────────────────────────────────
    //  退出登录
    // ──────────────────────────────────────────
    public void logout(String accessToken) {
        if (jwtUtil.validateToken(accessToken) && jwtUtil.isAccessToken(accessToken)) {
            blacklistService.add(accessToken, jwtUtil.extractExpiration(accessToken));
            log.info("用户已退出登录: {}", jwtUtil.extractUsername(accessToken));
        }
        // Token 本身已过期或非法，无需处理，直接忽略
    }

    // ──────────────────────────────────────────
    //  刷新 Token
    // ──────────────────────────────────────────
    public TokenRefreshResponse refresh(String refreshToken) {
        // 1. 格式 & 签名 & 过期校验
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new RuntimeException("refreshToken 已过期，请重新登录");
        }
        // 2. 必须是 RefreshToken 类型
        if (!jwtUtil.isRefreshToken(refreshToken)) {
            throw new RuntimeException("Token 类型错误");
        }
        // 3. 黑名单检查（主动退出后 RefreshToken 也不可用）
        if (blacklistService.isBlacklisted(refreshToken)) {
            throw new RuntimeException("Token 已注销，请重新登录");
        }

        // 4. 签发新 AccessToken（RefreshToken 未过期不重新签发）
        String username     = jwtUtil.extractUsername(refreshToken);
        Long   userId       = jwtUtil.extractUserId(refreshToken);
        String newAccessToken = jwtUtil.generateAccessToken(username, userId);

        log.debug("Token 刷新成功: {}", username);
        return new TokenRefreshResponse(newAccessToken);
    }

    // ──────────────────────────────────────────
    //  获取当前用户信息
    // ──────────────────────────────────────────
    public UserInfoResponse getUserInfo(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return new UserInfoResponse(user.getId(), user.getUsername(), user.getEmail());
    }

    // ──────────────────────────────────────────
    //  私有：构造双 Token 响应
    // ──────────────────────────────────────────
    private AuthResponse buildAuthResponse(User user) {
        String access  = jwtUtil.generateAccessToken(user.getUsername(), user.getId());
        String refresh = jwtUtil.generateRefreshToken(user.getUsername(), user.getId());
        return new AuthResponse(access, refresh, user.getUsername(), user.getId());
    }
}
