package com.comic.common;

/** AI调用异常 */
public class AiCallException extends RuntimeException {
    public AiCallException(String message) {
        super(message);
    }
    public AiCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
