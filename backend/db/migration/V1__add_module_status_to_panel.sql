-- Add module status fields to panel table for PRODUCING stage
ALTER TABLE panel ADD COLUMN text_status VARCHAR(20) DEFAULT 'NONE';
ALTER TABLE panel ADD COLUMN image_status VARCHAR(20) DEFAULT 'NONE';
ALTER TABLE panel ADD COLUMN video_status VARCHAR(20) DEFAULT 'NONE';
