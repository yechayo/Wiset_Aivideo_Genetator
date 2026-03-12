package com.comic.common;

import lombok.Data;

/**
 * 统一 API 响应结构
 * 所有接口都返回这个格式：{ "code": 200, "message": "success", "data": ... }
 */
@Data
public class Result<T> {

    private int    code;
    private String message;
    private T      data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code    = 200;
        r.message = "success";
        r.data    = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String message) {
        Result<T> r = new Result<>();
        r.code    = 400;
        r.message = message;
        return r;
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code    = code;
        r.message = message;
        return r;
    }
}
