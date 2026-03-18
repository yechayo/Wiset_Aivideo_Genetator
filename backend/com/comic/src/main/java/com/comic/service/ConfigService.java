package com.comic.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.comic.dto.request.ConfigSaveRequest;
import com.comic.entity.SystemConfig;
import com.comic.mapper.SystemConfigMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final SystemConfigMapper configMapper;
    private final Gson gson = new Gson();

    /**
     * 获取配置值
     */
    public String getConfigValue(String configKey) {
        SystemConfig config = configMapper.selectOne(
                new QueryWrapper<SystemConfig>().eq("config_key", configKey)
        );
        return config != null ? config.getConfigValue() : null;
    }

    /**
     * 获取配置值（带默认值）
     */
    public String getConfigValue(String configKey, String defaultValue) {
        String value = getConfigValue(configKey);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取JSON类型配置值
     */
    public JsonObject getConfigAsJson(String configKey) {
        String value = getConfigValue(configKey);
        if (value == null) {
            return null;
        }
        try {
            return JsonParser.parseString(value).getAsJsonObject();
        } catch (Exception e) {
            log.error("解析JSON配置失败: {}", configKey, e);
            return null;
        }
    }

    /**
     * 保存或更新配置
     */
    public void saveConfig(ConfigSaveRequest dto) {
        SystemConfig existingConfig = configMapper.selectOne(
                new QueryWrapper<SystemConfig>().eq("config_key", dto.getConfigKey())
        );

        if (existingConfig != null) {
            // 更新
            existingConfig.setConfigValue(dto.getConfigValue());
            existingConfig.setConfigType(dto.getConfigType());
            existingConfig.setDescription(dto.getDescription());
            existingConfig.setUpdateTime(LocalDateTime.now());
            configMapper.updateById(existingConfig);
        } else {
            // 新增
            SystemConfig newConfig = new SystemConfig();
            newConfig.setConfigKey(dto.getConfigKey());
            newConfig.setConfigValue(dto.getConfigValue());
            newConfig.setConfigType(dto.getConfigType() != null ? dto.getConfigType() : "string");
            newConfig.setDescription(dto.getDescription());
            newConfig.setCreateTime(LocalDateTime.now());
            newConfig.setUpdateTime(LocalDateTime.now());
            configMapper.insert(newConfig);
        }
    }

    /**
     * 获取所有配置
     */
    public List<SystemConfig> getAllConfigs() {
        return configMapper.selectList(null);
    }

    /**
     * 删除配置
     */
    public void deleteConfig(String configKey) {
        configMapper.delete(
                new QueryWrapper<SystemConfig>().eq("config_key", configKey)
        );
    }
}
