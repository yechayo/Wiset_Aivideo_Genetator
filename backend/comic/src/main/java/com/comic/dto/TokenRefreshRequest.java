package com.comic.dto;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TokenRefreshRequest {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
