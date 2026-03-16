-- ====================================
-- 系统配置表
-- ====================================
CREATE TABLE IF NOT EXISTS t_system_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key VARCHAR(100) UNIQUE NOT NULL,
    config_value TEXT,
    config_type VARCHAR(50) DEFAULT 'string',
    description VARCHAR(255),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 初始化默认配置
INSERT OR IGNORE INTO t_system_config (config_key, config_value, config_type, description) VALUES
('llm.provider', 'claude', 'string', 'LLM提供商'),
('llm.claude.api_key', '', 'string', 'Claude API Key'),
('llm.gemini.api_key', '', 'string', 'Gemini API Key'),
('llm.deepseek.api_key', '', 'string', 'DeepSeek API Key'),
('llm.glm.api_key', '', 'string', 'GLM API Key'),
('image.provider', 'stability', 'string', '图片生成提供商'),
('video.provider', 'yunwu', 'string', '视频生成提供商'),
('video.sora.api_key', '', 'string', 'Sora API Key'),
('oss.provider', 'local', 'string', 'OSS提供商（local, minio, aliyun）');

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_config_key ON t_system_config(config_key);
