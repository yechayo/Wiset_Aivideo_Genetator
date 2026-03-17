package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "comic.oss")
public class OssProperties {

    private String accessKeyId;

    private String accessKeySecret;

    private String bucketName;

    private String endpoint;

    private String dir = "comic/images/";
}
