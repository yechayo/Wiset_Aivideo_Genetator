/** 片段流水线状态 */
export type SegmentPipelineStep = 'pending' | 'scene_ready' | 'comic_review' | 'comic_approved' | 'video_generating' | 'video_completed' | 'video_failed';

/** 分镜详细信息 */
export interface PanelData {
  panelId: string;
  /** panelInfo 中的 panel_id（如 "p1"、"p2"），用于匹配 panelPlan */
  planPanelId: string;
  composition: string;
  shotType: string;
  cameraAngle: string;
  pacing: string;
  dialogue: string;
  characters: any[];
  background: any;
  imagePromptHint: string;
  sfx: string[];
  duration?: number;
}

/** 片段状态 */
export interface SegmentState {
  segmentIndex: number;
  title: string;
  synopsis: string;
  sceneThumbnail: string | null;
  characterAvatars: { charId: string; name: string; avatarUrl: string }[];
  pipelineStep: SegmentPipelineStep;
  comicUrl: string | null;
  videoUrl: string | null;
  feedback: string;
  panelData?: PanelData;
  videoTaskId?: string | null;
  videoOffPeak?: boolean | null;
}

/** 剧集状态 */
export interface EpisodeState {
  episodeId: number;
  episodeIndex: number;
  title: string;
  /** panelPlan JSON 解析后的 scene_summary 映射：panel_id → scene_summary */
  sceneSummaryMap: Record<string, string>;
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
