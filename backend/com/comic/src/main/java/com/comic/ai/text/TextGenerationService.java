package com.comic.ai.text;

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
     * 获取服务名称
     */
    String getServiceName();

    /**
     * 获取当前可用并发槽位
     */
    int getAvailableConcurrentSlots();
}
