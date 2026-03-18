package com.comic.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {
    private Long userId;
    private String username;
    private String email;
    private String avatarUrl;

    // 兼容 AuthService 调用
    public UserInfoResponse(Long userId, String username, String email) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.avatarUrl = null;
    }
}
