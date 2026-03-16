-- ====================================
-- 核心业务表
-- ====================================

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
    series_id TEXT,
    script_revision_note TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_project_user_id ON project(user_id);
CREATE INDEX IF NOT EXISTS idx_project_status ON project(status);
CREATE INDEX IF NOT EXISTS idx_project_series_id ON project(series_id);

-- 剧集表
CREATE TABLE IF NOT EXISTS episode (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    series_id TEXT NOT NULL,
    episode_num INTEGER NOT NULL,
    title TEXT,
    outline_node TEXT,
    storyboard_json TEXT,
    status TEXT,
    error_msg TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_episode_series_id ON episode(series_id);
CREATE INDEX IF NOT EXISTS idx_episode_status ON episode(status);

-- 角色表（使用 t_character 避免关键字冲突）
CREATE TABLE IF NOT EXISTS t_character (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    series_id TEXT,
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
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_character_series_id ON t_character(series_id);
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
