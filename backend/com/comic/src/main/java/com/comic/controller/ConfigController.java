package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.ConfigSaveRequest;
import com.comic.entity.SystemConfig;
import com.comic.service.ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Tag(name = "系统配置管理")
@SecurityRequirement(name = "bearerAuth")
public class ConfigController {

    private final ConfigService configService;

    /**
     * 获取所有配置
     */
    @GetMapping
    @Operation(summary = "获取所有配置")
    public Result<List<SystemConfig>> getAllConfigs() {
        return Result.ok(configService.getAllConfigs());
    }

    /**
     * 获取单个配置值
     */
    @GetMapping("/{configKey}")
    @Operation(summary = "获取配置值")
    public Result<String> getConfigValue(@PathVariable String configKey) {
        String value = configService.getConfigValue(configKey);
        return Result.ok(value);
    }

    /**
     * 保存或更新配置
     */
    @PostMapping
    @Operation(summary = "保存或更新配置")
    public Result<Void> saveConfig(@Valid @RequestBody ConfigSaveRequest dto) {
        configService.saveConfig(dto);
        return Result.ok();
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/{configKey}")
    @Operation(summary = "删除配置")
    public Result<Void> deleteConfig(@PathVariable String configKey) {
        configService.deleteConfig(configKey);
        return Result.ok();
    }
}
