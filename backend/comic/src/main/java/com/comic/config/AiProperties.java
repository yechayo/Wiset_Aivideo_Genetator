package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "comic.ai")
public class AiProperties {
    private int maxConcurrent = 3;
    private int timeoutSeconds = 60;
    private int retryTimes = 3;
}
