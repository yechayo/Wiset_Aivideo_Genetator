import { useEffect, useRef, useCallback } from 'react';
import { getEpisodes, getBatchProductionStatuses } from '../../../../services/episodeService';

interface SseProgressCallbacks {
  onEpisodeScriptDone: (data: { episodeNum: number; title: string; totalEpisodes: number; completedEpisodes: number }) => void;
  onEpisodeStoryboardDone: (data: { episodeId: number; episodeNum: number; shotsCount: number }) => void;
  onEpisodeGridStatus: (data: { episodeId: number; gridStatus: string }) => void;
  onStatusChange: (data: { from?: string; to?: string }) => void;
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

  const handleReconnect = useCallback(async () => {
    if (!projectId) return;
    try {
      // 全量同步：重新加载 episodes 和 production statuses
      const res = await getEpisodes(projectId);
      if (res.code === 0 || res.code === 200) {
        const items = res.data?.items || [];
        for (const ep of items) {
          if (ep.id) {
            getBatchProductionStatuses(projectId, ep.id).catch(() => {});
          }
        }
      }
    } catch {
      // 静默失败
    }
  }, [projectId]);

  useEffect(() => {
    if (!projectId) return;

    const url = `/api/projects/${projectId}/status/stream`;
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
        } else if (eventType === 'episode:grid_status') {
          cbRef.current.onEpisodeGridStatus(data);
        } else {
          // 兼容原有的项目级状态变更（无 eventType 或其他）
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
      es.close();
      esRef.current = null;
    };
  }, [projectId, handleReconnect]);

  return { reconnect: handleReconnect };
}
