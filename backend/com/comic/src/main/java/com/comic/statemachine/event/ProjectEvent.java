package com.comic.statemachine.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 项目状态机事件（复杂事件）
 * 用于需要携带额外数据的场景
 */
@Getter
public class ProjectEvent extends ApplicationEvent {

    private final String projectId;
    private final String eventType;
    private final String userId;
    private final Object payload;

    public ProjectEvent(Object source, String projectId, String eventType, String userId) {
        this(source, projectId, eventType, userId, null);
    }

    public ProjectEvent(Object source, String projectId, String eventType, String userId, Object payload) {
        super(source);
        this.projectId = projectId;
        this.eventType = eventType;
        this.userId = userId;
        this.payload = payload;
    }

    /**
     * 创建简单事件（无额外数据）
     */
    public static ProjectEvent of(String projectId, String eventType, String userId) {
        return new ProjectEvent(null, projectId, eventType, userId);
    }

    /**
     * 创建带负载的事件
     */
    public static ProjectEvent withPayload(String projectId, String eventType, String userId, Object payload) {
        return new ProjectEvent(null, projectId, eventType, userId, payload);
    }
}
