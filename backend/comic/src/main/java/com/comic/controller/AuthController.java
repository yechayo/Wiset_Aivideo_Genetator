package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.*;
import com.comic.service.auth.AuthService;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口
 *
 * POST /api/auth/register  注册
 * POST /api/auth/login     登录
 * POST /api/auth/logout    退出（需要登录）
 * POST /api/auth/refresh   刷新 Token
 * GET  /api/auth/me        获取当前用户（需要登录）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册 */
    @PostMapping("/register")
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return Result.ok(authService.register(req));
    }

    /** 登录 */
    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    /**
     * 退出登录
     * 前端需在 Header 携带：Authorization: Bearer <accessToken>
     * 成功后该 Token 立即失效，前端需同步清除本地存储
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authHeader) {
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Authorization Header 格式错误");
        }
        authService.logout(authHeader.substring(7));
        return Result.ok();
    }

    /**
     * 刷新 AccessToken
     * 前端在 AccessToken 过期（收到 401）后调用此接口
     */
    @PostMapping("/refresh")
    public Result<TokenRefreshResponse> refresh(@Valid @RequestBody TokenRefreshRequest req) {
        return Result.ok(authService.refresh(req.getRefreshToken()));
    }

    /**
     * 获取当前登录用户信息
     * @AuthenticationPrincipal 由 Spring Security 自动注入，无需手动解析 Token
     */
    @GetMapping("/me")
    public Result<UserInfoResponse> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails) {
        return Result.ok(authService.getUserInfo(userDetails.getUsername()));
    }
}
