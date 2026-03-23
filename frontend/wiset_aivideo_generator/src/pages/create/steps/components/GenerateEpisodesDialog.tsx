import { useEffect, useState } from 'react';
import styles from './GenerateEpisodesDialog.module.less';

interface GenerateEpisodesDialogProps {
  open: boolean;
  chapter: string;
  defaultEpisodeCount?: number;
  onClose: () => void;
  onConfirm: (episodeCount: number, modificationSuggestion?: string) => void;
  loading?: boolean;
}

/**
 * 生成剧集确认对话框
 */
const GenerateEpisodesDialog = ({
  open,
  chapter,
  defaultEpisodeCount = 4,
  onClose,
  onConfirm,
  loading = false
}: GenerateEpisodesDialogProps) => {
  const [episodeCount, setEpisodeCount] = useState(defaultEpisodeCount);
  const [modificationSuggestion, setModificationSuggestion] = useState('');

  useEffect(() => {
    if (open) {
      setEpisodeCount(defaultEpisodeCount);
      setModificationSuggestion('');
    }
  }, [open, chapter, defaultEpisodeCount]);

  if (!open) return null;

  const handleConfirm = () => {
    onConfirm(episodeCount, modificationSuggestion.trim() || undefined);
  };

  const handleClose = () => {
    if (!loading) {
      onClose();
    }
  };

  const extractEpisodeRange = (chapterTitle: string) => {
    const match = chapterTitle.match(/(?:第\s*)?(\d+)\s*[-~～—–]\s*(\d+)\s*集/);
    if (match) {
      const start = parseInt(match[1], 10);
      const end = parseInt(match[2], 10);
      return { start, end, count: end - start + 1 };
    }
    return null;
  };

  const episodeRange = extractEpisodeRange(chapter);

  return (
    <div className={styles.overlay} onClick={handleClose}>
      <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <h3 className={styles.title}>生成剧集</h3>
          <button className={styles.closeButton} onClick={handleClose} disabled={loading}>
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M18 6L6 18M6 6l12 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </button>
        </div>

        <div className={styles.content}>
          <div className={styles.chapterInfo}>
            <span className={styles.label}>章节：</span>
            <span className={styles.chapterName}>{chapter}</span>
          </div>

          {episodeRange && (
            <div className={styles.episodeRangeInfo}>
              <span className={styles.infoText}>
                根据章节标题，该章节包含第 {episodeRange.start}-{episodeRange.end} 集，共 {episodeRange.count} 集
              </span>
            </div>
          )}

          <div className={styles.field}>
            <label className={styles.fieldLabel}>生成集数</label>
            <input
              type="number"
              className={styles.input}
              value={episodeCount}
              onChange={(e) => setEpisodeCount(Math.max(1, parseInt(e.target.value, 10) || 1))}
              min="1"
              max="10"
              disabled={loading}
            />
            <span className={styles.fieldHint}>建议生成 2-4 集</span>
          </div>

          <div className={styles.field}>
            <label className={styles.fieldLabel}>修改建议（可选）</label>
            <textarea
              className={styles.textarea}
              value={modificationSuggestion}
              onChange={(e) => setModificationSuggestion(e.target.value)}
              placeholder="如有特殊要求，请在此输入..."
              rows={3}
              disabled={loading}
            />
            <span className={styles.fieldHint}>可以指定某些情节、角色互动等要求</span>
          </div>
        </div>

        <div className={styles.footer}>
          <button
            className={styles.cancelButton}
            onClick={handleClose}
            disabled={loading}
          >
            取消
          </button>
          <button
            className={styles.confirmButton}
            onClick={handleConfirm}
            disabled={loading || episodeCount < 1}
          >
            {loading ? (
              <>
                <span className={styles.spinner}></span>
                生成中...
              </>
            ) : (
              '确认生成'
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

export default GenerateEpisodesDialog;
