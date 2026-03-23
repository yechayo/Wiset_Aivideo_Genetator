package com.comic.ai.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DeepSeekTextServiceTest {

    @Test
    @DisplayName("parseResponse throws a clear error when DeepSeek stops because of length")
    void parseResponse_shouldThrowClearErrorWhenFinishReasonIsLength() {
        DeepSeekTextService service = new DeepSeekTextService(mock(OkHttpClient.class), new ObjectMapper());

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> invokeParseResponse(service, truncatedLengthResponse()));

        assertTrue(error.getMessage().contains("truncated"));
    }

    private String truncatedLengthResponse() {
        return "{"
                + "\"choices\":[{"
                + "\"finish_reason\":\"length\","
                + "\"message\":{"
                + "\"content\":\"{\\\"episode\\\":1,\\\"title\\\":\\\"EP1\\\"\""
                + "}"
                + "}]"
                + "}";
    }

    private String invokeParseResponse(DeepSeekTextService service, String responseBody) {
        try {
            Method method = DeepSeekTextService.class.getDeclaredMethod("parseResponse", String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, responseBody);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
