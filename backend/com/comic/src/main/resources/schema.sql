-- ============================================================
--  漫画创作系统 - 完整数据库初始化脚本
--  SQLite 数据库
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    email TEXT,
    deleted INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 项目表
CREATE TABLE IF NOT EXISTS project (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id TEXT UNIQUE NOT NULL,
    user_id TEXT NOT NULL,
    story_prompt TEXT,
    genre TEXT,
    target_audience TEXT,
    total_episodes INTEGER,
    episode_duration INTEGER,
    status TEXT,
    script_revision_note TEXT,
    -- 两级剧本生成新增字段
    script_outline TEXT,
    selected_chapter TEXT,
    episodes_per_chapter INTEGER DEFAULT 4,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_project_user_id ON project(user_id);
CREATE INDEX IF NOT EXISTS idx_project_status ON project(status);

-- 剧集表
CREATE TABLE IF NOT EXISTS episode (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id TEXT NOT NULL,
    episode_num INTEGER NOT NULL,
    title TEXT,
    outline_node TEXT,
    storyboard_json TEXT,
    -- 两级剧本生成新增字段
    content TEXT,
    characters TEXT,
    key_items TEXT,
    continuity_note TEXT,
    chapter_title TEXT,
    status TEXT,
    error_msg TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_episode_project_id ON episode(project_id);
CREATE INDEX IF NOT EXISTS idx_episode_status ON episode(status);

-- 角色表（使用 t_character 避免关键字冲突）
CREATE TABLE IF NOT EXISTS t_character (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    char_id TEXT,
    name TEXT NOT NULL,
    role TEXT,
    profile_json TEXT,
    current_state_json TEXT,
    project_id TEXT,
    personality TEXT,
    appearance TEXT,
    background TEXT,
    standard_image_url TEXT,
    confirmed INTEGER DEFAULT 0,
    locked INTEGER DEFAULT 0,
    -- 角色图片生成相关字段
    expression_status TEXT,
    three_view_status TEXT,
    expression_prompt TEXT,
    three_view_prompt TEXT,
    expression_error TEXT,
    three_view_error TEXT,
    is_generating_expression INTEGER DEFAULT 0,
    is_generating_three_view INTEGER DEFAULT 0,
    -- 视觉风格
    visual_style TEXT DEFAULT '3D',
    expression_grid_url TEXT,
    three_view_grid_url TEXT,
    expression_grid_prompt TEXT,
    three_view_grid_prompt TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_character_project_id ON t_character(project_id);

-- 任务表
CREATE TABLE IF NOT EXISTS job (
    job_id TEXT PRIMARY KEY,
    job_type TEXT NOT NULL,
    status TEXT NOT NULL,
    progress INTEGER DEFAULT 0,
    progress_msg TEXT,
    input_params TEXT,
    result_data TEXT,
    error_msg TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_status ON job(status);
CREATE INDEX IF NOT EXISTS idx_job_type ON job(job_type);

-- 系统配置表
CREATE TABLE IF NOT EXISTS t_system_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key TEXT UNIQUE NOT NULL,
    config_value TEXT,
    config_type TEXT DEFAULT 'string',
    description TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_config_key ON t_system_config(config_key);

-- 初始化默认配置
INSERT OR IGNORE INTO t_system_config (config_key, config_value, config_type, description) VALUES
('llm.provider', 'deepseek', 'string', 'LLM提供商'),
('image.provider', 'glm', 'string', '图片生成提供商'),
('video.provider', 'yunwu', 'string', '视频生成提供商'),
('oss.provider', 'local', 'string', 'OSS提供商');

-- 文件管理表
CREATE TABLE IF NOT EXISTS t_file (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name TEXT NOT NULL,
    file_path TEXT,
    file_url TEXT,
    file_size INTEGER,
    file_type TEXT,
    mime_type TEXT,
    storage_type TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_file_name ON t_file(file_name);
CREATE INDEX IF NOT EXISTS idx_file_type ON t_file(file_type);
CREATE INDEX IF NOT EXISTS idx_storage_type ON t_file(storage_type);

-- 任务执行跟踪表
CREATE TABLE IF NOT EXISTS t_task_execution (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT UNIQUE NOT NULL,
    task_type TEXT,
    project_id INTEGER,
    node_id TEXT,
    status TEXT DEFAULT 'running',
    progress INTEGER DEFAULT 0,
    result TEXT,
    error TEXT,
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME,
    FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_task_execution ON t_task_execution(task_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON t_task_execution(status);
CREATE INDEX IF NOT EXISTS idx_task_project ON t_task_execution(project_id);
CREATE INDEX IF NOT EXISTS idx_task_type ON t_task_execution(task_type);
