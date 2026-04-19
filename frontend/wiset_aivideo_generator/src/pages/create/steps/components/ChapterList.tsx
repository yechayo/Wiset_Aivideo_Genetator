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
  isBatchGenerating?: boolean;
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
  isBatchGenerating,
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
    return episodes.filter((ep) => ep.episodeInfo?.chapterTitle === chapter);
  };

  // 从章节字符串中提取可读标题（去掉 ### 前缀和 markdown 语法）
  const getDisplayName = (chapter: string) => {
    return chapter.replace(/^#{1,6}\s*/, '').trim();
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
                  <span className={styles.chapterName}>{getDisplayName(chapter)}</span>
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
                    <EpisodeCard key={episode.id || episode.episodeInfo?.episodeNum} episode={episode} />
                  ))}
                  {/* 展开状态下也显示重新生成按钮 */}
                  {generated && (
                    <div className={styles.regenerateActions}>
                      <button
                        className={styles.regenerateButton}
                        onClick={() => onGenerateClick?.(chapter)}
                        disabled={isBatchGenerating}
                      >
                        <svg className={styles.icon} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                          <path d="M1 4v6h6M23 20v-6h-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                          <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                        重新生成
                      </button>
                    </div>
                  )}
                </div>
              )}

              {/* 生成按钮（折叠状态） */}
              {pending && !expanded && (
                <div className={styles.chapterActions}>
                  <button
                    className={styles.generateButton}
                    onClick={() => onGenerateClick?.(chapter)}
                    disabled={isBatchGenerating}
                  >
                    <svg className={styles.icon} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path d="M12 4V16M12 4L8 8M12 4L16 8M4 20H20" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                    生成剧集
                  </button>
                </div>
              )}

              {/* 重新生成按钮（已生成章节，折叠状态） */}
              {generated && !expanded && (
                <div className={styles.chapterActions}>
                  <button
                    className={styles.regenerateButton}
                    onClick={(e) => { e.stopPropagation(); onGenerateClick?.(chapter); }}
                    disabled={isBatchGenerating}
                  >
                    <svg className={styles.icon} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path d="M1 4v6h6M23 20v-6h-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                      <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                    重新生成
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

/** 将文本中的 [爽点:xxx] 渲染为带下划线的段落 + badge 标签 */
const renderContentWithMarks = (text: string) => {
  const parts = text.split(/(\[爽点:[^\]]*\])/g);
  const elements: React.ReactNode[] = [];

  for (let i = 0; i < parts.length; i++) {
    const part = parts[i];
    const match = part.match(/^\[爽点:([^\]]*)\]$/);
    if (match) {
      elements.push(
        <span key={`m-${i}`} className={styles.shuangdianBadge}>{match[1]}</span>
      );
    } else if (part) {
      const nextPart = parts[i + 1];
      const hasMark = nextPart?.match(/^\[爽点:[^\]]*\]$/);
      const cleanText = part.replace(/\n+/g, ' ').replace(/\s+/g, ' ');

      if (hasMark && cleanText.trim()) {
        elements.push(
          <span key={`t-${i}`} className={styles.textSegment}>{cleanText}</span>
        );
      } else {
        elements.push(<span key={`t-${i}`}>{cleanText}</span>);
      }
    }
  }

  return elements;
};

/**
 * 剧集卡片组件
 */
const EpisodeCard = ({ episode }: { episode: Episode }) => {
  const [expanded, setExpanded] = useState(false);
  const isLongContent = episode.episodeInfo?.content && episode.episodeInfo.content.length > 200;

  const displayContent = expanded
    ? episode.episodeInfo?.content || ''
    : episode.episodeInfo?.content?.substring(0, 200) || '';

  return (
    <div className={styles.episodeCard}>
      <div className={styles.episodeHeader}>
        <span className={styles.episodeTitle}>
          第{episode.episodeInfo?.episodeNum || '-'}集：{episode.episodeInfo?.title}
        </span>
      </div>
      <div className={styles.episodeContent}>
        {episode.episodeInfo?.content && (
          <>
            <div className={styles.content}>
              {renderContentWithMarks(displayContent)}
              {isLongContent && !expanded && '...'}
            </div>
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
          {episode.episodeInfo?.characters && (
            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>角色：</span>
              <span className={styles.metaValue}>{episode.episodeInfo.characters}</span>
            </div>
          )}
          {episode.episodeInfo?.keyItems && (
            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>物品：</span>
              <span className={styles.metaValue}>{episode.episodeInfo.keyItems}</span>
            </div>
          )}
          {episode.episodeInfo?.visualStyleNote && (
            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>视觉风格：</span>
              <span className={styles.metaValue}>{episode.episodeInfo.visualStyleNote}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default ChapterList;
