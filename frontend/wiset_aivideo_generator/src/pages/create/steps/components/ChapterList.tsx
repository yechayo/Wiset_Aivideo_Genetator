import { useState } from 'react';
import { ChevronDownIcon, ChevronRightIcon } from '../../../../components/icons/Icons';
import styles from './ChapterList.module.less';
import type { Episode } from '../../../../services/types/project.types';

interface ChapterListProps {
  chapters: string[];
  generatedChapters: string[];
  pendingChapters: string[];
  episodes: Episode[];
  onGenerateClick?: (chapter: string) => void;
  onRegenerateClick?: (chapter: string) => void;
}

/**
 * 章节列表组件
 * 显示所有章节，区分已生成和待生成状态
 */
const ChapterList = ({
  chapters,
  generatedChapters,
  pendingChapters,
  episodes,
  onGenerateClick,
  onRegenerateClick
}: ChapterListProps) => {
  const [expandedChapters, setExpandedChapters] = useState<Set<string>>(new Set());

  const toggleChapter = (chapter: string) => {
    setExpandedChapters((prev) => {
      const next = new Set(prev);
      if (next.has(chapter)) {
        next.delete(chapter);
      } else {
        next.add(chapter);
      }
      return next;
    });
  };

  const isGenerated = (chapter: string) => generatedChapters.includes(chapter);
  const isPending = (chapter: string) => pendingChapters.includes(chapter);

  // 获取章节对应的剧集
  const getChapterEpisodes = (chapter: string) => {
    return episodes.filter((ep) => ep.chapterTitle === chapter);
  };

  if (chapters.length === 0) {
    return (
      <div className={styles.emptyState}>
        <p className={styles.emptyText}>暂无章节信息</p>
        <p className={styles.emptyHint}>剧本大纲可能还在生成中...</p>
      </div>
    );
  }

  return (
    <div className={styles.chapterList}>
      <div className={styles.header}>
        <h3 className={styles.title}>章节列表</h3>
        <span className={styles.count}>
          已生成: {generatedChapters.length} / {chapters.length}
        </span>
      </div>

      <div className={styles.list}>
        {chapters.map((chapter) => {
          const generated = isGenerated(chapter);
          const pending = isPending(chapter);
          const expanded = expandedChapters.has(chapter);
          const chapterEpisodes = getChapterEpisodes(chapter);

          return (
            <div key={chapter} className={styles.chapterItem}>
              <button
                className={styles.chapterHeader}
                onClick={() => toggleChapter(chapter)}
              >
                <div className={styles.chapterHeaderLeft}>
                  {generated || chapterEpisodes.length > 0 ? (
                    <ChevronDownIcon className={`${styles.chevron} ${expanded ? styles.open : ''}`} />
                  ) : (
                    <ChevronRightIcon className={styles.chevron} />
                  )}
                  <span className={styles.chapterName}>{chapter}</span>
                </div>
                <div className={styles.chapterHeaderRight}>
                  {generated ? (
                    <span className={`${styles.status} ${styles.generated}`}>已生成</span>
                  ) : pending ? (
                    <span className={`${styles.status} ${styles.pending}`}>待生成</span>
                  ) : (
                    <span className={styles.status}>未开始</span>
                  )}
                  {chapterEpisodes.length > 0 && (
                    <span className={styles.episodeCount}>{chapterEpisodes.length} 集</span>
                  )}
                </div>
              </button>

              {/* 展开的剧集列表 */}
              {expanded && chapterEpisodes.length > 0 && (
                <div className={styles.episodeList}>
                  {chapterEpisodes.map((episode) => (
                    <EpisodeCard key={episode.id || episode.episodeNum} episode={episode} />
                  ))}
                  {/* 重新生成按钮 */}
                  {generated && onRegenerateClick && (
                    <div className={styles.regenerateActions}>
                      <button
                        className={styles.regenerateButton}
                        onClick={() => onRegenerateClick(chapter)}
                      >
                        <svg className={styles.icon} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                          <path d="M4 4V8H8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                          <path d="M20 20V16H16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                          <path d="M4 8C4 8 5.5 4 12 4C18.5 4 20 8 20 8M20 16C20 16 18.5 20 12 20C5.5 20 4 16 4 16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                        重新生成
                      </button>
                    </div>
                  )}
                </div>
              )}

              {/* 生成按钮 */}
              {pending && !expanded && (
                <div className={styles.chapterActions}>
                  <button
                    className={styles.generateButton}
                    onClick={() => onGenerateClick?.(chapter)}
                  >
                    <svg className={styles.icon} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path d="M12 4V16M12 4L8 8M12 4L16 8M4 20H20" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                    生成剧集
                  </button>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

/**
 * 剧集卡片组件
 */
const EpisodeCard = ({ episode }: { episode: Episode }) => {
  const [expanded, setExpanded] = useState(false);
  const isLongContent = episode.content && episode.content.length > 200;

  return (
    <div className={styles.episodeCard}>
      <div className={styles.episodeHeader}>
        <span className={styles.episodeTitle}>
          第{episode.episodeNum || '-'}集：{episode.title}
        </span>
      </div>
      <div className={styles.episodeContent}>
        {episode.content && (
          <>
            <p className={styles.content}>
              {expanded ? episode.content : episode.content.substring(0, 200)}
              {isLongContent && !expanded && '...'}
            </p>
            {isLongContent && (
              <button
                className={styles.toggleButton}
                onClick={() => setExpanded(!expanded)}
              >
                {expanded ? '收起' : '展开全文'}
              </button>
            )}
          </>
        )}
        <div className={styles.episodeMeta}>
          {episode.characters && (
            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>角色：</span>
              <span className={styles.metaValue}>{episode.characters}</span>
            </div>
          )}
          {episode.keyItems && (
            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>物品：</span>
              <span className={styles.metaValue}>{episode.keyItems}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default ChapterList;
