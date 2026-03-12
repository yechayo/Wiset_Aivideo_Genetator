-- 用户表
-- IF NOT EXISTS 保证重启不报错
CREATE TABLE IF NOT EXISTS user
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    username   TEXT    NOT NULL UNIQUE,
    password   TEXT    NOT NULL,
    email      TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0,         -- 逻辑删除：0正常 1删除
    created_at DATETIME         DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME         DEFAULT CURRENT_TIMESTAMP
);
