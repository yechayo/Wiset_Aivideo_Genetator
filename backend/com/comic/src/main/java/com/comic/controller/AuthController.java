package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.LoginRequest;
import com.comic.dto.request.RegisterRequest;
import com.comic.dto.request.TokenRefreshRequest;
import com.comic.dto.response.AuthResponse;
import com.comic.dto.response.TokenRefreshResponse;
import com.comic.dto.response.UserInfoResponse;
import com.comic.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "退出登录", description = "退出登录并使当前 Token 失效。需要在 Header 中传递：Authorization: Bearer {accessToken}")
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
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的详细信息。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<UserInfoResponse> getCurrentUser(
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        return Result.ok(authService.getUserInfo(userDetails.getUsername()));
    }
}
