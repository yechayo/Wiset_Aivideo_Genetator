/** 片段流水线状态 */
export type SegmentPipelineStep = 'pending' | 'scene_ready' | 'comic_review' | 'comic_approved' | 'video_generating' | 'video_completed' | 'video_failed';

/** 片段状态 */
export interface SegmentState {
  segmentIndex: number;
  title: string;
  synopsis: string;
  sceneThumbnail: string | null;
  characterAvatars: { name: string; avatarUrl: string }[];
  pipelineStep: SegmentPipelineStep;
  comicUrl: string | null;
  videoUrl: string | null;
  feedback: string;
}

/** 剧集状态 */
export interface EpisodeState {
  episodeId: number;
  episodeIndex: number;
  title: string;
  segments: SegmentState[];
}

/** 章节状态 */
export interface ChapterState {
  chapterIndex: number;
  title: string;
  episodes: EpisodeState[];
}

/** 片段子卡片展开状态（手风琴模式：同时只展开一个剧集和一个片段）*/
export interface ExpansionState {
  expandedEpisodeId: number | null;
  expandedSegmentKey: string | null; // "episodeId-segmentIndex"
}
