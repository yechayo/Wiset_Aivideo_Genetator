import { useEffect, useState, useCallback, useRef } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import styles from './Step4page.module.less';
import type { Project, CharacterListItem, CharacterStatus } from '../../../services';
import { isApiSuccess } from '../../../services';
import type { StepContentProps } from '../types';
import { useCreateStore } from '../../../stores/createStore';
import {
  getCharacters,
  getCharacterStatus,
  generateAllImages,
  generateImage,
  retryGeneration,
  confirmImages,
} from '../../../services/characterService';
import { advanceStatus } from '../../../services/projectService';

interface Step4pageProps extends StepContentProps {
  project: Project;
}

const Step4page = ({ project }: Step4pageProps) => {
  const { statusInfo, isLoadingStatus, syncStatus } = useCreateStore();
  const projectId = project.projectId;
  const prefersReducedMotion = useReducedMotion();

  const [characters, setCharacters] = useState<CharacterListItem[]>([]);
  const [statusMap, setStatusMap] = useState<Map<string, CharacterStatus>>(new Map());
  const [error, setError] = useState('');
  const [actionLoading, setActionLoading] = useState(false);
  const [expandedCharId, setExpandedCharId] = useState<string | null>(null);
  const [generatingIds, setGeneratingIds] = useState<Set<string>>(new Set());
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const statusCode = statusInfo?.statusCode || '';

  // 加载角色列表
  const loadCharacters = useCallback(async () => {
    if (!projectId) return;
    try {
      const res = await getCharacters(projectId);
      if (isApiSuccess(res) && res.data) {
        const items = res.data.items;
        setCharacters(items);

        // 并行获取每个角色的详情（图片生成状态）
        const statuses = await Promise.all(
          items.map(char => getCharacterStatus(projectId, char.charId)
            .then(r => (isApiSuccess(r) && r.data ? r.data : null))
            .catch(() => null))
        );
        const map = new Map<string, CharacterStatus>();
        statuses.forEach((s, i) => { if (s) map.set(items[i].charId, s); });
        setStatusMap(map);

        // 根据服务端状态回收本地生成集合
        const nextGeneratingIds = new Set<string>();
        items.forEach((char, i) => {
          const st = statuses[i];
          if (st?.isGeneratingExpression || st?.isGeneratingThreeView
            || char.expressionStatus === 'GENERATING' || char.threeViewStatus === 'GENERATING') {
            nextGeneratingIds.add(char.charId);
          }
        });

        setGeneratingIds(nextGeneratingIds);
        setError('');
      }
    } catch (err: any) {
      setError(err.message || '获取角色列表失败');
    }
  }, [projectId]);

  // 启动角色状态轮询（有角色正在生成时）
  const startPolling = useCallback(() => {
    if (pollingRef.current) return;
    pollingRef.current = setInterval(loadCharacters, 3000);
  }, [loadCharacters]);

  const stopPolling = useCallback(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  // statusCode 变化时加载角色数据
  // IMAGE_GENERATING 期间持续轮询
  useEffect(() => {
    loadCharacters();

    if (statusCode === 'IMAGE_GENERATING') {
      pollingRef.current = setInterval(loadCharacters, 3000);
    } else {
      stopPolling();
    }

    return () => stopPolling();
  }, [statusCode, loadCharacters, stopPolling]);

  // 有角色在生成中也启动轮询
  useEffect(() => {
    if (generatingIds.size > 0) {
      startPolling();
    }
    // 不需要在 cleanup 中停止，statusCode 变化的 effect 会处理
  }, [generatingIds.size, startPolling]);

  // ========== 操作处理 ==========

  const handleStartGeneration = async () => {
    if (!projectId) return;
    setActionLoading(true);
    try {
      // CHARACTER_CONFIRMED → IMAGE_GENERATING 是原子自动推进，
      // 调用 confirmCharacters 触发即可
      await confirmCharacters(projectId);
    } catch (err: any) {
      alert(err.message || '启动生成失败');
    } finally {
      setActionLoading(false);
    }
  };

  const handleConfirmImages = async () => {
    if (!projectId) return;
    const confirmed = window.confirm('确认后将锁定所有素材图片，无法再重新生成。请确认图片无误。');
    if (!confirmed) return;
    setActionLoading(true);
    try {
      await confirmImages(projectId);
    } catch (err: any) {
      alert(err.message || '确认失败');
    } finally {
      setActionLoading(false);
    }
  };

  const handleStartProduction = async () => {
    if (!projectId) return;
    const confirmed = window.confirm('确认后将锁定素材并开始生成分镜，完成后进入分镜审核。是否继续？');
    if (!confirmed) return;
    setActionLoading(true);
    try {
      await confirmImages(projectId);
      // IMAGE_REVIEW → ASSET_LOCKED → PANEL_GENERATING 是原子推进，
      // 立即同步后端状态，触发路由跳转到 step 5
      if (projectId) {
        await syncStatus(projectId);
      }
    } catch (err: any) {
      alert(err.message || '启动分镜生成失败');
    } finally {
      setActionLoading(false);
    }
  };

  const handleRetryGeneration = async () => {
    if (!projectId) return;
    setActionLoading(true);
    try {
      await advanceStatus(projectId, 'forward', 'retry');
    } catch (err: any) {
      alert(err.message || '重试失败');
    } finally {
      setActionLoading(false);
    }
  };

  // 一键生成全部角色图片（跳过已完成的）
  const handleGenerateAll = async () => {
    if (!projectId) return;
    const ids = new Set<string>();
    for (const char of characters) {
      const isSupporting = char.role === '配角';
      const allDone = char.threeViewStatus === 'COMPLETED' && (isSupporting || char.expressionStatus === 'COMPLETED');
      if (allDone) continue; // 跳过已完成的角色

      ids.add(char.charId);
      try {
        await generateAllImages(projectId, char.charId);
      } catch {
        // 单个失败不阻断
      }
    }
    setGeneratingIds(ids);
  };

  // 单个角色生成图片
  const handleGenerateChar = async (charId: string, type: 'expression' | 'threeView') => {
    if (!projectId) return;
    setGeneratingIds(prev => new Set(prev).add(charId));
    try {
      await generateImage(projectId, charId, type);
    } catch (err: any) {
      alert(err.message || '生成失败');
      setGeneratingIds(prev => { const next = new Set(prev); next.delete(charId); return next; });
    }
  };

  // 单项重试
  const handleRetryChar = async (charId: string, type: 'expression' | 'threeView') => {
    if (!projectId) return;
    setGeneratingIds(prev => new Set(prev).add(charId));
    try {
      await retryGeneration(projectId, charId, type);
    } catch (err: any) {
      alert(err.message || '重试失败');
      setGeneratingIds(prev => { const next = new Set(prev); next.delete(charId); return next; });
    }
  };

  const handleExpand = (charId: string) => {
    setExpandedCharId(prev => prev === charId ? null : charId);
  };

  const getGenStatusText = (status: string | undefined, label: string) => {
    switch (status) {
      case 'GENERATING': return `${label}生成中...`;
      case 'COMPLETED': return `${label}已生成`;
      case 'FAILED': return `${label}生成失败`;
      default: return `${label}未生成`;
    }
  };

  const getRoleClass = (role: string) => {
    switch (role) {
      case '主角': return styles.protagonist;
      case '反派': return styles.antagonist;
      default: return styles.supporting;
    }
  };

  // ========== 渲染角色卡片 ==========
  const renderCard = (char: CharacterListItem, mode: 'generating' | 'review' | 'locked' | 'preview') => {
    const isExpanded = expandedCharId === char.charId;
    const st = statusMap.get(char.charId);
    const isSupporting = char.role === '配角';
    const isGenerating = generatingIds.has(char.charId);

    // 根据角色自身状态判断
    const charAllDone = char.threeViewStatus === 'COMPLETED' && (isSupporting || char.expressionStatus === 'COMPLETED');
    const charAnyFailed = char.threeViewStatus === 'FAILED' || char.expressionStatus === 'FAILED';
    const charAnyGenerating = char.threeViewStatus === 'GENERATING' || char.expressionStatus === 'GENERATING';

    const cardClass = [
      styles.characterCard,
      isExpanded ? styles.expanded : '',
      mode === 'locked' ? styles.locked : '',
      charAllDone ? styles.completed : '',
      charAnyFailed ? styles.failed : '',
      (charAnyGenerating || isGenerating) ? styles.generating : '',
    ].filter(Boolean).join(' ');

    // 状态徽章
    const statusBadge = mode === 'locked' ? (
      <span className={styles.lockedBadge}>已锁定</span>
    ) : mode === 'preview' ? null : charAllDone ? (
      <span className={styles.statusBadgeDone}>已完成</span>
    ) : charAnyFailed ? (
      <span className={styles.statusBadgeFailed}>有失败</span>
    ) : (charAnyGenerating || isGenerating) ? (
      <div className={styles.cardStatusBadge}>
        <span className={`${styles.statusDot} ${styles.generating}`} />
        生成中
      </div>
    ) : (
      <span className={styles.statusBadgePending}>待生成</span>
    );

    // 渲染单项图片区域（表情或三视图）
    const renderImageItem = (label: string, imageUrl?: string, status?: string, error?: string, type?: 'expression' | 'threeView') => (
      <div className={`${styles.imageItem} ${type === 'threeView' ? styles.threeViewItem : styles.expressionItem}`}>
        <div className={styles.imageItemHeader}>
          <span className={styles.imageLabel}>{label}</span>
          {status === 'COMPLETED' && <span className={`${styles.imageStatusTag} ${styles.tagDone}`}>已完成</span>}
          {status === 'GENERATING' && <span className={`${styles.imageStatusTag} ${styles.tagGenerating}`}><span className={`${styles.statusDot} ${styles.generating}`} />生成中</span>}
          {status === 'FAILED' && <span className={`${styles.imageStatusTag} ${styles.tagFailed}`}>失败</span>}
        </div>
        <div className={type === 'threeView' ? `${styles.imagePreview} ${styles.threeView}` : styles.imagePreview}>
          {imageUrl ? (
            <img src={imageUrl} alt={label} />
          ) : status === 'FAILED' ? (
            <div className={styles.imagePlaceholder}>
              <span>生成失败</span>
              {error && <span className={styles.imageError}>{error}</span>}
            </div>
          ) : (
            <div className={styles.imagePlaceholder}>
              <span>未生成</span>
            </div>
          )}
        </div>
        {!isGenerating && mode !== 'locked' && (
          status === 'GENERATING' ? null : status === 'COMPLETED' ? (
            <button
              className={styles.retryBtn}
              onClick={() => handleRetryChar(char.charId, type!)}
            >
              重新生成
            </button>
          ) : status === 'FAILED' ? (
            <button
              className={styles.retryBtn}
              onClick={() => handleRetryChar(char.charId, type!)}
            >
              重试生成
            </button>
          ) : type === 'expression' && char.threeViewStatus !== 'COMPLETED' ? (
            <span className={styles.generateHint}>需先生成三视图</span>
          ) : (
            <button
              className={styles.generateBtn}
              onClick={() => handleGenerateChar(char.charId, type!)}
            >
              生成{type === 'expression' ? '表情' : '三视图'}
            </button>
          )
        )}
      </div>
    );

    const expandedContent = isExpanded ? (
      <motion.div
        className={styles.expandedContent}
        initial={{ opacity: prefersReducedMotion ? 1 : 0, y: prefersReducedMotion ? 0 : -8 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: prefersReducedMotion ? 1 : 0, y: prefersReducedMotion ? 0 : -8 }}
        transition={{ duration: prefersReducedMotion ? 0 : 0.25, ease: [0.4, 0, 0.2, 1] }}
      >
        {(mode === 'generating' || mode === 'review') && (
          <div className={styles.statusRow}>
            {!isSupporting && (
              <div className={styles.statusItem}>
                <span className={`${styles.statusDot} ${
                  char.expressionStatus === 'COMPLETED' ? styles.completed :
                  char.expressionStatus === 'FAILED' ? styles.failed :
                  char.expressionStatus === 'GENERATING' ? styles.generating : styles.pending
                }`} />
                {getGenStatusText(char.expressionStatus ?? undefined, '表情')}
                {st?.expressionError && <span className={styles.imageError}>({st?.expressionError})</span>}
              </div>
            )}
            <div className={styles.statusItem}>
              <span className={`${styles.statusDot} ${
                char.threeViewStatus === 'COMPLETED' ? styles.completed :
                char.threeViewStatus === 'FAILED' ? styles.failed :
                char.threeViewStatus === 'GENERATING' ? styles.generating : styles.pending
              }`} />
              {getGenStatusText(char.threeViewStatus ?? undefined, '三视图')}
              {st?.threeViewError && <span className={styles.imageError}>({st?.threeViewError})</span>}
            </div>
          </div>
        )}

        <div className={styles.imageRow}>
          {!isSupporting && renderImageItem(
            '九宫格表情',
            st?.expressionGridUrl,
            char.expressionStatus ?? undefined,
            st?.expressionError,
            'expression'
          )}
          {renderImageItem(
            '三视图',
            st?.threeViewGridUrl,
            char.threeViewStatus ?? undefined,
            st?.threeViewError,
            'threeView'
          )}
        </div>

      </motion.div>
    ) : null;

    return (
      <div
        key={char.charId}
        className={cardClass}
        role={isExpanded ? undefined : 'button'}
        tabIndex={isExpanded ? -1 : 0}
        aria-expanded={isExpanded}
        aria-label={`${char.name} - ${char.role}`}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleExpand(char.charId); } }}
      >
        <div className={styles.cardHeader} onClick={() => handleExpand(char.charId)}>
          <div className={styles.cardAvatar}>
            {st?.threeViewGridUrl ? <img src={st?.threeViewGridUrl} alt={char.name} /> : <span>{char.name.charAt(0)}</span>}
          </div>
          <div className={styles.cardInfo}>
            <h3 className={styles.cardName}>{char.name}</h3>
            <span className={`${styles.cardRole} ${getRoleClass(char.role)}`}>{char.role}</span>
          </div>
          {statusBadge}
        </div>
        <AnimatePresence initial={false}>{expandedContent}</AnimatePresence>
      </div>
    );
  };

  // ========== 主渲染 ==========
  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>图片生成</h1>
        <p className={styles.subtitle}>共 {characters.length} 个角色，点击角色卡片查看素材图片</p>
      </div>

      {isLoadingStatus ? (
        <div className={styles.loadingState}>
          <div className={styles.spinner}></div>
          <p>正在加载数据...</p>
        </div>
      ) : error && !characters.length ? (
        <div className={styles.errorSection}>
          <p>{error}</p>
          <button className={styles.retryButton} onClick={() => { setError(''); loadCharacters(); }}>重试</button>
        </div>
      ) : statusCode === 'IMAGE_GENERATING_FAILED' ? (
        <div className={styles.errorSection}>
          <p>{statusInfo?.statusDescription || '图片生成失败'}</p>
          <button className={styles.retryButton} onClick={handleRetryGeneration} disabled={actionLoading}>
            {actionLoading ? '重试中...' : '重新生成'}
          </button>
        </div>
      ) : statusCode === 'CHARACTER_CONFIRMED' ? (
        <>
          <div className={styles.guideSection}>
            <div className={styles.guideIcon}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.455 2.456L21.75 6l-1.036.259a3.375 3.375 0 00-2.455 2.456z" />
              </svg>
            </div>
            <p className={styles.guideText}>角色配置已确认，接下来将自动生成所有角色的素材图片</p>
            <p className={styles.guideHint}>系统将为每个角色生成三视图和九宫格表情图（配角仅生成三视图）</p>
            <button className={styles.startButton} onClick={handleStartGeneration} disabled={actionLoading}>
              {actionLoading ? '启动中...' : '开始生成图片'}
            </button>
          </div>
          {characters.length > 0 && (
            <div className={styles.characterGrid}>{characters.map(c => renderCard(c, 'preview'))}</div>
          )}
          <div className={styles.bottomActions}>
            <button className={styles.startButton} onClick={handleStartGeneration} disabled={actionLoading}>
              {actionLoading ? '启动中...' : '开始生成图片'}
            </button>
          </div>
        </>
      ) : statusCode === 'IMAGE_GENERATING' ? (
        <>
          <div className={styles.characterGrid}>{characters.map(c => renderCard(c, 'generating'))}</div>
          <div className={styles.bottomActions}>
            <button
              className={styles.generateAllBtn}
              onClick={handleGenerateAll}
              disabled={generatingIds.size > 0}
            >
              {generatingIds.size > 0 ? <><span className={styles.miniSpinner} />生成中...</> : '一键全部生成'}
            </button>
          </div>
        </>
      ) : statusCode === 'IMAGE_REVIEW' ? (
        <>
          <div className={styles.characterGrid}>{characters.map(c => renderCard(c, 'review'))}</div>
          <div className={styles.bottomActions}>
            <button
              className={styles.generateAllBtn}
              onClick={handleGenerateAll}
              disabled={generatingIds.size > 0}
            >
              {generatingIds.size > 0 ? <><span className={styles.miniSpinner} />生成中...</> : '一键全部生成'}
            </button>
            <button className={styles.confirmButton} onClick={handleConfirmImages} disabled={actionLoading || generatingIds.size > 0}>
              {actionLoading ? '确认中...' : '确认图片，锁定素材'}
            </button>
          </div>
        </>
      ) : statusCode === 'ASSET_LOCKED' ? (
        <>
          <div className={styles.lockedSection}>
            <div className={styles.lockedSummary}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z" />
              </svg>
              所有素材已锁定，可以开始分镜审核
            </div>
          </div>
          <div className={styles.characterGrid}>{characters.map(c => renderCard(c, 'locked'))}</div>
          <div className={styles.bottomActions}>
            <button className={styles.productionButton} onClick={handleStartProduction} disabled={actionLoading}>
              {actionLoading ? '启动中...' : '进入分镜审核'}
            </button>
          </div>
        </>
      ) : (
        <div className={styles.emptyState}><p>等待进入图片生成阶段</p></div>
      )}
    </div>
  );
};

export default Step4page;
