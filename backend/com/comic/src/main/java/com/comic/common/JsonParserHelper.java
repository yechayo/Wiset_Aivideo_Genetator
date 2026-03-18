package com.comic.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JSON解析通用工具类
 * <p>
 * 提供统一的JsonNode解析、错误消息处理功能
 * <p>
 * 使用示例:
 * <pre>{@code
 * JsonNode root = jsonParserHelper.parse(responseBody);
 * String taskId = jsonParserHelper.getText(root, "id");
 * }</pre>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JsonParserHelper {

    private final ObjectMapper objectMapper;

    /**
     * 解析JSON字符串为JsonNode
     *
     * @param json JSON字符串
     * @return 解析后的JsonNode
     * @throws AiCallException 解析失败时抛出
     */
    public JsonNode parse(String json) throws AiCallException {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("JSON解析失败: {}", json, e);
            throw new AiCallException("JSON解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析JSON字符串（自动清理markdown代码块标记）
     *
     * @param json JSON字符串（可能包含```json或```标记）
     * @return 解析后的JsonNode
     * @throws AiCallException 解析失败时抛出
     */
    public JsonNode parseWithMarkdownCleanup(String json) throws AiCallException {
        String cleanJson = cleanMarkdownCodeBlock(json);
        return parse(cleanJson);
    }

    /**
     * 清理Markdown代码块标记
     *
     * @param json 可能包含markdown标记的JSON字符串
     * @return 清理后的JSON字符串
     */
    public String cleanMarkdownCodeBlock(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        String cleaned = json.trim();

        // 移除开头的 ```json 或 ```
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        // 移除结尾的 ```
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        return cleaned.trim();
    }

    /**
     * 安全获取文本字段（带默认值）
     *
     * @param node         JsonNode节点
     * @param field        字段名
     * @param defaultValue 默认值
     * @return 字段值或默认值
     */
    public String getText(JsonNode node, String field, String defaultValue) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText(defaultValue);
        }
        return defaultValue;
    }

    /**
     * 安全获取文本字段（无默认值）
     *
     * @param node  JsonNode节点
     * @param field 字段名
     * @return 字段值的Optional，不存在则返回Optional.empty()
     */
    public Optional<String> getText(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return Optional.of(node.get(field).asText());
        }
        return Optional.empty();
    }

    /**
     * 安全获取整数字段（带默认值）
     *
     * @param node         JsonNode节点
     * @param field        字段名
     * @param defaultValue 默认值
     * @return 字段值或默认值
     */
    public int getInt(JsonNode node, String field, int defaultValue) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asInt(defaultValue);
        }
        return defaultValue;
    }

    /**
     * 安全获取长整数字段（带默认值）
     *
     * @param node         JsonNode节点
     * @param field        字段名
     * @param defaultValue 默认值
     * @return 字段值或默认值
     */
    public long getLong(JsonNode node, String field, long defaultValue) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asLong(defaultValue);
        }
        return defaultValue;
    }

    /**
     * 安全获取布尔字段（带默认值）
     *
     * @param node         JsonNode节点
     * @param field        字段名
     * @param defaultValue 默认值
     * @return 字段值或默认值
     */
    public boolean getBoolean(JsonNode node, String field, boolean defaultValue) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asBoolean(defaultValue);
        }
        return defaultValue;
    }

    /**
     * 安全获取子节点
     *
     * @param node  JsonNode节点
     * @param field 字段名
     * @return 子节点的Optional，不存在则返回Optional.empty()
     */
    public Optional<JsonNode> getNode(JsonNode node, String field) {
        if (node != null && node.has(field)) {
            return Optional.of(node.get(field));
        }
        return Optional.empty();
    }

    /**
     * 获取数组中的第一个元素
     *
     * @param arrayNode 数组节点
     * @return 第一个元素的Optional，不存在则返回Optional.empty()
     */
    public Optional<JsonNode> getFirstElement(JsonNode arrayNode) {
        if (arrayNode != null && arrayNode.isArray() && arrayNode.size() > 0) {
            return Optional.of(arrayNode.get(0));
        }
        return Optional.empty();
    }

    /**
     * 按路径获取嵌套字段（如 "data.0.url" 表示获取 data[0].url）
     *
     * @param root  根节点
     * @param path  点分隔的路径
     * @return 字段值的Optional，不存在则返回Optional.empty()
     */
    public Optional<String> getTextByPath(JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return Optional.empty();
        }

        String[] parts = path.split("\\.");
        JsonNode current = root;

        for (String part : parts) {
            if (current == null || current.isNull()) {
                return Optional.empty();
            }

            // 尝试解析为数组索引
            try {
                int index = Integer.parseInt(part);
                if (current.isArray() && index >= 0 && index < current.size()) {
                    current = current.get(index);
                } else {
                    return Optional.empty();
                }
            } catch (NumberFormatException e) {
                // 普通字段
                if (current.has(part)) {
                    current = current.get(part);
                } else {
                    return Optional.empty();
                }
            }
        }

        if (current != null && !current.isNull()) {
            return Optional.of(current.asText());
        }
        return Optional.empty();
    }

    /**
     * 检查节点是否存在且非空
     *
     * @param node  JsonNode节点
     * @param field 字段名
     * @return 如果字段存在且非空返回true
     */
    public boolean hasNonEmptyField(JsonNode node, String field) {
        return node != null && node.has(field) && !node.get(field).isNull();
    }

    /**
     * 提取错误消息（从API错误响应中）
     *
     * @param errorJson 错误响应JSON
     * @return 错误消息
     */
    public String extractErrorMessage(String errorJson) {
        try {
            JsonNode root = parse(errorJson);

            // 尝试多种可能的错误字段名
            String[] errorFields = {"error", "message", "msg", "error_message", "errorMessage"};

            for (String field : errorFields) {
                Optional<String> error = getText(root, field);
                if (error.isPresent()) {
                    return error.get();
                }

                // 检查嵌套的 error.message
                Optional<JsonNode> errorNode = getNode(root, "error");
                if (errorNode.isPresent()) {
                    Optional<String> nestedMsg = getText(errorNode.get(), "message");
                    if (nestedMsg.isPresent()) {
                        return nestedMsg.get();
                    }
                }
            }

            return "未知错误";
        } catch (Exception e) {
            return "解析错误响应失败: " + errorJson;
        }
    }

    /**
     * 验证是否为有效的JSON
     *
     * @param json JSON字符串
     * @return 如果是有效JSON返回true
     */
    public boolean isValidJson(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取数组大小
     *
     * @param node JsonNode节点
     * @return 如果是数组返回大小，否则返回0
     */
    public int getArraySize(JsonNode node) {
        if (node != null && node.isArray()) {
            return node.size();
        }
        return 0;
    }

    /**
     * 检查节点是否为数组且非空
     *
     * @param node JsonNode节点
     * @return 如果是非空数组返回true
     */
    public boolean isNonEmptyArray(JsonNode node) {
        return node != null && node.isArray() && node.size() > 0;
    }
}
