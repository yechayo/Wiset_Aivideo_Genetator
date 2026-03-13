-- ====================================
-- 文件管理表
-- ====================================
CREATE TABLE IF NOT EXISTS t_file (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(500),
    file_url VARCHAR(500),
    file_size BIGINT,
    file_type VARCHAR(50),
    mime_type VARCHAR(100),
    storage_type VARCHAR(50) DEFAULT 'local',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_file_name ON t_file(file_name);
CREATE INDEX IF NOT EXISTS idx_file_type ON t_file(file_type);
CREATE INDEX IF NOT EXISTS idx_storage_type ON t_file(storage_type);
