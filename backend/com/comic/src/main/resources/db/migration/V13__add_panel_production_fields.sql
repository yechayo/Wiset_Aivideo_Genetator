-- 单分镜视频生产：新增各分镜阶段的URL列表字段
ALTER TABLE episode_production ADD COLUMN background_urls TEXT;
ALTER TABLE episode_production ADD COLUMN fusion_urls TEXT;
ALTER TABLE episode_production ADD COLUMN transition_urls TEXT;
ALTER TABLE episode_production ADD COLUMN tail_frame_urls TEXT;
