import { useEffect, useRef, useCallback } from 'react';
import { useAuthStore } from '../../../../stores/authStore';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

interface SseProgressCallbacks {
  onEpisodeScriptDone: (data: { episodeNum: number; title: string; totalEpisodes: number; completedEpisodes: number; stage?: string }) => void;
  onEpisodeStoryboardDone: (data: { episodeId: number; episodeNum: number; shotsCount: number }) => void;
  onEpisodePanelDone: (data: { episodeId: number; episodeNum: number; shotsCount: number }) => void;
  onEpisodeGridStatus: (data: { episodeId: number; episodeNum?: number; gridStatus: string }) => void;
  onPanelVideoDone?: (data: { episodeId: number; panelId: number; videoUrl: string }) => void;
  onPanelVideoFailed?: (data: { episodeId: number; panelId: number; error: string }) => void;
  onPanelTtsDone?: (data: { episodeId: number; panelId: number; ttsAudioUrl: string }) => void;
  onPanelTtsFailed?: (data: { episodeId: number; panelId: number; error: string }) => void;
  onPanelMergeDone?: (data: { episodeId: number; panelId: number; videoWithNarrationUrl: string }) => void;
  onPanelMergeFailed?: (data: { episodeId: number; panelId: number; error: string }) => void;
  onEpisodeComposed?: (data: { episodeId: number; composedVideoUrl: string }) => void;
  onStatusChange: (data: { from?: string; to?: string }) => void;
  /** SSE 重连后触发全量数据刷新 */
  onReconnect?: () => void;
}

/**
 * SSE 实时进度订阅 Hook
 * 连接 /api/projects/{projectId}/status/stream，根据 eventType 分发事件
 *
 * 注意：前端当前没有任何 SSE/EventSource 代码，这是全新实现。
 * 与现有的轮询机制并存：SSE 做增量推送，轮询在关键时刻做全量同步。
 */
export function useSseProgress(
  projectId: string | undefined,
  callbacks: SseProgressCallbacks,
) {
  const esRef = useRef<EventSource | null>(null);
  // 用 ref 持有最新回调，避免闭包引用过期的 state
  const cbRef = useRef(callbacks);
  cbRef.current = callbacks;

  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleReconnect = useCallback(async () => {
    if (!projectId) return;
    // 防抖：2秒内只触发一次重连刷新，避免频繁重连导致连刷新
    if (reconnectTimerRef.current) return;
    reconnectTimerRef.current = setTimeout(() => {
      reconnectTimerRef.current = null;
      cbRef.current.onReconnect?.();
    }, 2000);
  }, [projectId]);

  useEffect(() => {
    if (!projectId) return;

    // EventSource 不支持自定义 Header，通过 query 参数传递 token
    const token = useAuthStore.getState().accessToken;
    const url = `${API_BASE_URL}/api/projects/${projectId}/status/stream${token ? '?token=' + encodeURIComponent(token) : ''}`;
    const es = new EventSource(url);
    esRef.current = es;

    es.addEventListener('status-change', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        const eventType = data.eventType;

        if (eventType === 'episode:script_done') {
          cbRef.current.onEpisodeScriptDone(data);
        } else if (eventType === 'episode:storyboard_done') {
          cbRef.current.onEpisodeStoryboardDone(data);
        } else if (eventType === 'episode:panel_done') {
          cbRef.current.onEpisodePanelDone(data);
        } else if (eventType === 'episode:grid_status') {
          cbRef.current.onEpisodeGridStatus(data);
        } else if (eventType === 'panel:video_done') {
          cbRef.current.onPanelVideoDone?.(JSON.parse(event.data));
        } else if (eventType === 'panel:video_failed') {
          cbRef.current.onPanelVideoFailed?.(JSON.parse(event.data));
        } else if (eventType === 'panel:tts_done') {
          cbRef.current.onPanelTtsDone?.(JSON.parse(event.data));
        } else if (eventType === 'panel:tts_failed') {
          cbRef.current.onPanelTtsFailed?.(JSON.parse(event.data));
        } else if (eventType === 'panel:merge_done') {
          cbRef.current.onPanelMergeDone?.(JSON.parse(event.data));
        } else if (eventType === 'panel:merge_failed') {
          cbRef.current.onPanelMergeFailed?.(JSON.parse(event.data));
        } else if (eventType === 'episode:composed') {
          cbRef.current.onEpisodeComposed?.(JSON.parse(event.data));
        } else if (eventType === 'milestone-change') {
          // 里程碑变更：同步状态并刷新数据
          cbRef.current.onStatusChange(data);
        } else if (eventType === 'failure') {
          // 任务失败事件
          cbRef.current.onStatusChange(data);
        } else if (eventType === 'task-complete') {
          cbRef.current.onStatusChange(data);
        } else {
          // 兼容其他事件类型
          cbRef.current.onStatusChange(data);
        }
      } catch {
        // 解析失败忽略
      }
    });

    // 首次连接不触发全量同步（页面加载时 loadEpisodes 已处理）
    let isFirstOpen = true;
    es.onopen = () => {
      if (isFirstOpen) {
        isFirstOpen = false;
        return;
      }
      // 重连后全量同步
      handleReconnect();
    };

    es.onerror = () => {
      // EventSource 会自动重连，不需要手动处理
    };

    return () => {
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      es.close();
      esRef.current = null;
    };
  }, [projectId, handleReconnect]);

  return { reconnect: handleReconnect };
}
