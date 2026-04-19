package com.comic.ai.text;

import java.util.function.Consumer;

/**
 * 文本生成服务接口
 * 用于剧本生成、对话生成、世界观创建等文本类任务
 */
public interface TextGenerationService {

    /**
     * 生成文本（同步调用）
     *
     * @param systemPrompt 系统提示词（角色设定 + 格式约束）
     * @param userPrompt   用户提示词（具体任务）
     * @return AI 生成的文本内容
     */
    String generate(String systemPrompt, String userPrompt);

    /**
     * 生成文本（流式调用）
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 流式响应
     */
    String generateStream(String systemPrompt, String userPrompt);

    /**
     * 生成文本（流式调用 + 实时回调）
     * 每个 content chunk 到达时调用 chunkConsumer，方法仍返回完整拼接结果。
     *
     * @param systemPrompt  系统提示词
     * @param userPrompt    用户提示词
     * @param chunkConsumer 每个 content chunk 的回调（可为 null）
     * @return 完整拼接的文本内容
     */
    String generateStream(String systemPrompt, String userPrompt, Consumer<String> chunkConsumer);

    /**
     * 获取服务名称
     */
    String getServiceName();

    /**
     * 获取当前可用并发槽位
     */
    int getAvailableConcurrentSlots();
}
