-- ====================================
-- 任务执行记录表
-- ====================================
CREATE TABLE IF NOT EXISTS t_task_execution (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id VARCHAR(100) UNIQUE NOT NULL,
    task_type VARCHAR(50),
    project_id INTEGER,
    node_id VARCHAR(64),
    status VARCHAR(32) DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    result TEXT,
    error TEXT,
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME,
    FOREIGN KEY (project_id) REFERENCES t_project(id) ON DELETE SET NULL
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_task_execution ON t_task_execution(task_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON t_task_execution(status);
CREATE INDEX IF NOT EXISTS idx_task_project ON t_task_execution(project_id);
CREATE INDEX IF NOT EXISTS idx_task_type ON t_task_execution(task_type);
