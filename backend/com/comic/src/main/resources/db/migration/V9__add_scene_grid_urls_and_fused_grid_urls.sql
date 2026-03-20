-- P1-1: 多张网格图URL列表（JSON数组格式）
ALTER TABLE episode_production ADD COLUMN scene_grid_urls TEXT;

-- P1-1: 多张融合图URL列表（JSON数组格式）
ALTER TABLE episode_production ADD COLUMN fused_grid_urls TEXT;
