package com.comic.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 登录 / 注册成功后返回的双 Token 响应 */
@Data
@AllArgsConstructor
public class AuthResponse {
    private String accessToken;    // 短期，用于接口认证（2小时）
    private String refreshToken;   // 长期，用于换取新 AccessToken（7天）
    private String username;
    private Long   userId;
}
