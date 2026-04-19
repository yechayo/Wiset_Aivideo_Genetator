package com.comic.service.script;

import com.comic.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 剧集 JSON 解析工具 — 从 LLM 输出中提取单个剧集的 JSON 字段。
 * 独立于 ScriptService 以便单元测试。
 */
public class EpisodeJsonCleaner {

    private final ObjectMapper objectMapper;

    public EpisodeJsonCleaner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 清理 LLM 输出中的 markdown 包裹，然后解析 JSON。
     * 兼容 JSON 对象和单元素数组两种输出格式。
     *
     * @return 解析后的 JsonNode（始终是 Object 类型）
     */
    public JsonNode cleanAndParse(String rawJson) {
        String cleanJson = rawJson;

        // 清理 markdown 代码块标记
        if (cleanJson.startsWith("```json")) {
            cleanJson = cleanJson.substring(7);
        } else if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson.substring(3);
        }
        if (cleanJson.endsWith("```")) {
            cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
        }
        cleanJson = cleanJson.trim();

        try {
            JsonNode rootNode = objectMapper.readTree(cleanJson);

            if (rootNode.isArray()) {
                if (rootNode.size() == 0) {
                    throw new BusinessException("剧集 JSON 为空数组");
                }
                return rootNode.get(0);
            } else if (rootNode.isObject()) {
                return rootNode;
            } else {
                throw new BusinessException("剧集 JSON 格式不正确");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("解析剧集内容失败: " + e.getMessage());
        }
    }

    /**
     * 安全获取 JSON 字段值，缺失或 null 时返回默认值。
     */
    public static String getField(JsonNode node, String field, String defaultValue) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText(defaultValue);
        }
        return defaultValue;
    }
}
