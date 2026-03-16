package com.comic.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

// ──────────────────────────────────────────
//  注册请求
// ──────────────────────────────────────────
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度为 3-20 位")
    public String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 50, message = "密码至少 6 位")
    public String password;

    @Email(message = "邮箱格式不正确")
    public String email;
}
