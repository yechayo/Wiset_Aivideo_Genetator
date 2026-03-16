package com.comic;

import com.comic.dto.LoginRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试基类
 * 提供通用的测试配置和辅助方法
 */
@SpringBootTest
@AutoConfigureMockMvc
@Slf4j
public class BaseTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected static String accessToken;
    protected static String refreshToken;

    @BeforeAll
    static void setUp() throws Exception {
        // 这里可以添加全局的测试设置
        // 注意：由于这是静态方法，无法直接使用 mockMvc
        // 实际的登录操作应该在子类中完成
    }

    /**
     * 执行登录并保存 token
     * 子类可以在 @BeforeAll 或 @Test 中调用此方法
     */
    protected void performLogin(String username, String password) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        accessToken = json.at("/data/accessToken").asText();
        refreshToken = json.at("/data/refreshToken").asText();
    }

    /**
     * 获取测试用的 access token
     * 如果没有登录，会自动执行登录
     */
    protected String getTestAccessToken() throws Exception {
        if (accessToken == null) {
            // 使用测试账号登录
            // 注意：需要确保数据库中存在此用户，或者先注册
            try {
                performLogin("testuser", "123456");
            } catch (Exception e) {
                // 如果登录失败，可能需要先注册用户
                // 这里可以根据实际情况处理
            }
        }
        return accessToken;
    }

    /**
     * 如果需要则登录
     * 提供给子类使用的便捷方法
     */
    protected void loginIfNeeded() throws Exception {
        if (accessToken == null) {
            log.info("执行测试用户登录...");
            performLogin("testuser", "123456");
            log.info("登录成功，获取到 accessToken");
        }
    }
}