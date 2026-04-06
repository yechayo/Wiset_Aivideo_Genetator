import { useEffect, useState, useCallback, useRef } from 'react';
import styles from './Step3Merged.module.less';
import Select from '../../../components/Select';
import type { Project, CharacterListItem, CharacterStatus } from '../../../services';
import { isApiSuccess } from '../../../services';
import { advanceStatus } from '../../../services/projectService';
import {
  getCharacters,
  getCharacterStatus,
  extractCharacters,
  updateCharacter,
  deleteCharacter,
  generateImage,
  retryGeneration,
  confirmSingleCharacter,
  lockSingleCharacter,
  setVisualStyle,
} from '../../../services/characterService';
import { useCreateStore } from '../../../stores/createStore';

interface Step3MergedProps {
  project: Project;
}

type CharacterPhase = 'configuring' | 'generating' | 'review' | 'locked';

const ROLE_OPTIONS = [
  { value: '主角', label: '主角' },
  { value: '反派', label: '反派' },
  { value: '配角', label: '配角' },
];

const VISUAL_STYLE_OPTIONS = [
  { value: '3D', label: '3D 写实渲染' },
  { value: 'REAL', label: '照片级写实' },
  { value: 'ANIME', label: '日系2D动漫' },
  { value: 'MANGA', label: '日本漫画' },
  { value: 'INK', label: '中国水墨画' },
  { value: 'CYBERPUNK', label: '赛博朋克' },
];

const SPECIES_OPTIONS = [
  { value: 'HUMAN', label: '人类' },
  { value: 'ANTHRO_ANIMAL', label: '拟人化动物' },
  { value: 'CREATURE', label: '奇幻/科幻种族' },
  { value: 'ANIMAL', label: '真实动物' },
];

const Step3Merged = ({ project }: Step3MergedProps) => {
  const { statusInfo, isLoadingStatus, syncStatus, markExtractCalled, hasExtractCalled } = useCreateStore();
  const projectId = project.projectId;

  const [characters, setCharacters] = useState<CharacterListItem[]>([]);
  const [isExtracting, setIsExtracting] = useState(false);
  const isExtractingRef = useRef(false);
  const initialSyncRef = useRef(false);
  const [statusMap, setStatusMap] = useState<Map<string, CharacterStatus>>(new Map());
  const [error, setError] = useState('');
  const [expandedCharId, setExpandedCharId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<Partial<CharacterListItem>>({});
  const [saving, setSaving] = useState(false);
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

        // 自动提取：后端返回空列表且尚未提取过 → 触发提取
        // 放在 loadCharacters 回调中，确保是在 API 返回后才判断，避免竞态条件
        if (items.length === 0 && !isExtractingRef.current && !hasExtractCalled(projectId)) {
          isExtractingRef.current = true;
          markExtractCalled(projectId);
          setIsExtracting(true);
          extractCharacters(projectId)
            .then(() => loadCharacters())
            .catch((err) => console.error('提取角色失败:', err))
            .finally(() => {
              setIsExtracting(false);
              isExtractingRef.current = false;
            });
          return; // 提取中不继续加载详情
        }

        const statuses = await Promise.all(
          items.map(char => getCharacterStatus(projectId, char.charId)
            .then(r => (isApiSuccess(r) && r.data ? r.data : null))
            .catch(() => null))
        );
        const map = new Map<string, CharacterStatus>();
        statuses.forEach((s, i) => { if (s) map.set(items[i].charId, s); });
        setStatusMap(map);

        // 合并：后端 GENERATING + 本地标记（防止后端状态延迟导致丢失）
        setGeneratingIds(prev => {
          const next = new Set<string>();
          items.forEach((char, i) => {
            const st = statuses[i];
            if (st?.isGeneratingExpression || st?.isGeneratingThreeView
              || char.expressionStatus === 'GENERATING' || char.threeViewStatus === 'GENERATING') {
              next.add(char.charId);
            }
          });
          // 保留本地标记但后端还没反映的 ID（除非后端已完成/失败）
          prev.forEach(id => {
            const char = items.find(c => c.charId === id);
            if (!char) return; // 角色已删除，移除
            const done = char.threeViewStatus === 'COMPLETED'
              && (char.role === '配角' || char.expressionStatus === 'COMPLETED');
            const failed = char.threeViewStatus === 'FAILED' || char.expressionStatus === 'FAILED';
            if (!done && !failed) next.add(id);
          });
          return next;
        });
        setError('');
      }
    } catch (err: any) {
      setError(err.message || '获取角色列表失败');
    }
  }, [projectId, markExtractCalled, hasExtractCalled, extractCharacters]);

  // 轮询
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

  useEffect(() => {
    loadCharacters();
  }, [loadCharacters]);

  // Watch backend generating flag to control polling
  useEffect(() => {
    if (statusInfo?.isGenerating) {
      startPolling();
    } else {
      stopPolling();
    }
    return () => stopPolling();
  }, [statusInfo?.isGenerating, startPolling, stopPolling]);

  // 进入页面时先同步一次最新状态
  useEffect(() => {
    if (!projectId || initialSyncRef.current) return;
    initialSyncRef.current = true;
    syncStatus(projectId);
  }, [projectId, syncStatus]);

  useEffect(() => {
    if (generatingIds.size > 0) startPolling();
  }, [generatingIds.size, startPolling]);

  // 判断角色所处阶段（基于实际生成状态，不依赖后端 charStatus）
  const getCharPhase = useCallback((char: CharacterListItem): CharacterPhase => {
    const st = statusMap.get(char.charId);
    const isSupporting = char.role === '配角';
    const allDone = char.threeViewStatus === 'COMPLETED' && (isSupporting || char.expressionStatus === 'COMPLETED');

    if (char.imagesLocked) return 'locked';
    // 优先用实际生成状态判断，不依赖 charStatus（后端可能未及时更新）
    if (st?.isGeneratingExpression || st?.isGeneratingThreeView
      || char.expressionStatus === 'GENERATING' || char.threeViewStatus === 'GENERATING'
      || generatingIds.has(char.charId)) return 'generating';
    if (allDone) return 'review';
    if (char.threeViewStatus === 'FAILED' || char.expressionStatus === 'FAILED') return 'review';
    // charStatus 仅作为配置中的兜底提示
    if (char.charStatus === 'generating') return 'generating';
    return 'configuring';
  }, [statusMap, generatingIds]);

  // 展开/收起角色卡片
  const handleExpand = (charId: string) => {
    if (expandedCharId === charId) {
      setExpandedCharId(null);
      setEditForm({});
    } else {
      const char = characters.find(c => c.charId === charId);
      if (char) {
        setExpandedCharId(charId);
        setEditForm({
          name: char.name,
          role: char.role,
          personality: char.personality,
          appearance: char.appearance,
          voice: char.voice,
          background: char.background,
          species: char.species,
        });
      }
    }
  };

  // 表单修改
  const handleFormChange = (field: string, value: string) => {
    setEditForm(prev => ({ ...prev, [field]: value }));
  };

  // 保存角色配置
  const handleSave = async (charId: string) => {
    if (!projectId) return;
    setSaving(true);
    try {
      await updateCharacter(projectId, charId, editForm);
      await loadCharacters();
      setExpandedCharId(null);
      setEditForm({});
    } catch (err: any) {
      alert(err.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  // 删除角色
  const handleDelete = async (charId: string) => {
    if (!projectId) return;
    const confirmed = window.confirm('确定要删除该角色吗？');
    if (!confirmed) return;
    try {
      await deleteCharacter(projectId, charId);
      if (expandedCharId === charId) {
        setExpandedCharId(null);
        setEditForm({});
      }
      await loadCharacters();
    } catch (err: any) {
      alert(err.message || '删除失败');
    }
  };

  // 设置视觉风格
  const handleStyleChange = async (charId: string, style: string) => {
    if (!projectId) return;
    setCharacters(prev => prev.map(c => {
      if (c.charId !== charId) return c;
      return { ...c, visualStyle: style };
    }));
    try {
      await setVisualStyle(projectId, charId, style);
    } catch {
      await loadCharacters();
    }
  };

  // 单个角色生成图片
  const handleGenerateChar = async (charId: string, type: 'expression' | 'threeView') => {
    if (!projectId) return;
    setGeneratingIds(prev => new Set(prev).add(charId));
    try {
      await generateImage(projectId, charId, type);
      loadCharacters();
    } catch (err: any) {
      alert(err.message || '生成失败');
      setGeneratingIds(prev => { const next = new Set(prev); next.delete(charId); return next; });
      loadCharacters();
    }
  };

  // 单项重试
  const handleRetryChar = async (charId: string, type: 'expression' | 'threeView') => {
    if (!projectId) return;
    setGeneratingIds(prev => new Set(prev).add(charId));
    try {
      await retryGeneration(projectId, charId, type);
      loadCharacters();
    } catch (err: any) {
      alert(err.message || '重试失败');
      setGeneratingIds(prev => { const next = new Set(prev); next.delete(charId); return next; });
      loadCharacters();
    }
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

  // 进度统计
  const phaseCount = {
    configuring: characters.filter(c => getCharPhase(c) === 'configuring').length,
    generating: characters.filter(c => getCharPhase(c) === 'generating').length,
    review: characters.filter(c => getCharPhase(c) === 'review').length,
    locked: characters.filter(c => getCharPhase(c) === 'locked').length,
  };

  // ========== 渲染角色卡片 ==========
  const renderCard = (char: CharacterListItem) => {
    const phase = getCharPhase(char);
    const isExpanded = expandedCharId === char.charId;
    const st = statusMap.get(char.charId);
    const isSupporting = char.role === '配角';
    const isGenerating = generatingIds.has(char.charId);

    const charAllDone = char.threeViewStatus === 'COMPLETED' && (isSupporting || char.expressionStatus === 'COMPLETED');
    const charAnyFailed = char.threeViewStatus === 'FAILED' || char.expressionStatus === 'FAILED';
    const charAnyGenerating = char.threeViewStatus === 'GENERATING' || char.expressionStatus === 'GENERATING';

    const cardClass = [
      styles.characterCard,
      isExpanded ? styles.expanded : '',
      phase === 'locked' ? styles.locked : '',
      charAllDone ? styles.completed : '',
      charAnyFailed ? styles.failed : '',
      (charAnyGenerating || isGenerating) ? styles.generating : '',
    ].filter(Boolean).join(' ');

    // 阶段徽章
    const phaseBadge = phase === 'locked' ? (
      <span className={styles.lockedBadge}>已锁定</span>
    ) : phase === 'generating' ? (
      <div className={styles.cardStatusBadge}>
        <span className={`${styles.statusDot} ${styles.generating}`} />
        生成中
      </div>
    ) : phase === 'review' ? (
      <span className={styles.statusBadgeDone}>待审核</span>
    ) : (
      <span className={styles.statusBadgePending}>配置中</span>
    );

    // 渲染图片项
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
          ) : status === 'GENERATING' ? (
            <div className={styles.imagePlaceholder}>
              <div className={styles.spinner} />
              <span>生成中...</span>
            </div>
          ) : status === 'FAILED' ? (
            <div className={styles.imagePlaceholder}>
              <span>生成失败</span>
              {error && <span className={styles.imageError}>{error}</span>}
            </div>
          ) : (
            <div className={styles.imagePlaceholder}><span>未生成</span></div>
          )}
        </div>
        {!isGenerating && phase !== 'locked' && (
          status === 'GENERATING' ? null : status === 'COMPLETED' ? (
            <button className={styles.retryBtn} onClick={() => handleRetryChar(char.charId, type!)}>重新生成</button>
          ) : status === 'FAILED' ? (
            <button className={styles.retryBtn} onClick={() => handleRetryChar(char.charId, type!)}>重试生成</button>
          ) : type === 'expression' && char.threeViewStatus !== 'COMPLETED' ? (
            <span className={styles.generateHint}>需先生成三视图</span>
          ) : (
            <button className={styles.generateBtn} onClick={() => handleGenerateChar(char.charId, type!)}>生成{type === 'expression' ? '表情' : '三视图'}</button>
          )
        )}
      </div>
    );

    // 配置表单（configuring 阶段展开时显示）
    const renderConfigForm = () => (
      <div className={styles.expandedContent}>
        <div className={styles.editHeader}>
          <h4 className={styles.editTitle}>角色详情</h4>
          <div className={styles.editHeaderActions}>
            <button className={styles.deleteBtn} onClick={() => handleDelete(char.charId)}>删除角色</button>
            <button className={styles.collapseBtn} onClick={() => { setExpandedCharId(null); setEditForm({}); }}>收起</button>
          </div>
        </div>
        <div className={styles.expandedLeft}>
          <div className={styles.editForm}>
            <div className={styles.formGroup}>
              <label className={styles.formLabel}>角色名称</label>
              <input className={styles.formInput} value={editForm.name || ''} onChange={(e) => handleFormChange('name', e.target.value)} placeholder="角色名称" />
            </div>
            <div className={styles.formGroup}>
              <label className={styles.formLabel}>角色定位</label>
              <Select options={ROLE_OPTIONS} value={editForm.role || ''} onChange={(v) => handleFormChange('role', v)} />
            </div>
            <div className={styles.formGroup}>
              <label className={styles.formLabel}>视觉风格</label>
              <Select options={VISUAL_STYLE_OPTIONS} value={char.visualStyle || '3D'} onChange={(v) => handleStyleChange(char.charId, v)} />
            </div>
            <div className={styles.formGroup}>
              <label className={styles.formLabel}>物种类型</label>
              <Select options={SPECIES_OPTIONS} value={editForm.species || 'HUMAN'} onChange={(v) => handleFormChange('species', v)} />
            </div>
            <div className={styles.formGroup}>
              <label className={styles.formLabel}>性格描述</label>
              <input className={styles.formInput} value={editForm.personality || ''} onChange={(e) => handleFormChange('personality', e.target.value)} placeholder="角色性格" />
            </div>
            <div className={`${styles.formGroup} ${styles.fullWidth}`}>
              <label className={styles.formLabel}>外貌描述</label>
              <textarea className={styles.formTextarea} value={editForm.appearance || ''} onChange={(e) => handleFormChange('appearance', e.target.value)} placeholder="角色外貌特征" />
            </div>
            <div className={`${styles.formGroup} ${styles.fullWidth}`}>
              <label className={styles.formLabel}>声音描述</label>
              <textarea className={styles.formTextarea} value={editForm.voice || ''} onChange={(e) => handleFormChange('voice', e.target.value)} placeholder="角色声音特征" />
            </div>
            <div className={`${styles.formGroup} ${styles.fullWidth}`}>
              <label className={styles.formLabel}>背景故事</label>
              <textarea className={styles.formTextarea} value={editForm.background || ''} onChange={(e) => handleFormChange('background', e.target.value)} placeholder="角色背景故事" />
            </div>
          </div>
          <div className={styles.editHeaderActions} style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            <button className={styles.saveBtn} onClick={() => handleSave(char.charId)} disabled={saving}>
              {saving ? '保存中...' : '保存修改'}
            </button>
            <button className={styles.startButton} onClick={async () => {
              if (!projectId) return;
              setGeneratingIds(prev => new Set(prev).add(char.charId));
              try {
                await confirmSingleCharacter(projectId, char.charId);
              } catch (err: any) {
                alert(err.message || '生成失败');
                setGeneratingIds(prev => { const next = new Set(prev); next.delete(char.charId); return next; });
              }
            }} disabled={generatingIds.has(char.charId)}>
              {generatingIds.has(char.charId) ? '生成中...' : '生成素材'}
            </button>
          </div>
        </div>
      </div>
    );

    // 图片状态区域（generating/review/locked 阶段展开时显示）
    const renderImageContent = () => (
      <div className={styles.expandedContent}>
        {(phase === 'generating' || phase === 'review') && (
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
          {!isSupporting && renderImageItem('九宫格表情', st?.expressionGridUrl, char.expressionStatus ?? undefined, st?.expressionError, 'expression')}
          {renderImageItem('三视图', st?.threeViewGridUrl, char.threeViewStatus ?? undefined, st?.threeViewError, 'threeView')}
        </div>
        {phase === 'review' && (
          <button className={styles.confirmCharBtn} onClick={() => projectId && lockSingleCharacter(projectId, char.charId).then(loadCharacters)}>
            确认锁定
          </button>
        )}
      </div>
    );

    const expandedContent = isExpanded ? (phase === 'configuring' ? renderConfigForm() : renderImageContent()) : null;

    return (
      <div key={char.charId} className={cardClass} role={isExpanded ? undefined : 'button'} tabIndex={isExpanded ? -1 : 0}
        aria-expanded={isExpanded} aria-label={`${char.name} - ${char.role}`}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleExpand(char.charId); } }}>
        <div className={styles.cardHeader} onClick={() => handleExpand(char.charId)}>
          <div className={styles.cardAvatar}>
            {st?.threeViewGridUrl ? <img src={st?.threeViewGridUrl} alt={char.name} /> : <span>{char.name.charAt(0)}</span>}
          </div>
          <div className={styles.cardInfo}>
            <h3 className={styles.cardName}>{char.name}</h3>
            <span className={`${styles.cardRole} ${getRoleClass(char.role)}`}>{char.role}</span>
          </div>
          {phaseBadge}
        </div>
        {expandedContent}
      </div>
    );
  };

  // ========== 一键操作 ==========
  const [batchLoading, setBatchLoading] = useState(false);

  const handleGenerateAll = async () => {
    if (!projectId) return;
    const unconfirmed = characters.filter(c => getCharPhase(c) === 'configuring');
    if (unconfirmed.length === 0) return;
    setBatchLoading(true);
    // 立即标记所有为生成中，让 UI 展示动画
    setGeneratingIds(prev => {
      const next = new Set(prev);
      unconfirmed.forEach(c => next.add(c.charId));
      return next;
    });
    try {
      await Promise.all(
        unconfirmed.map(char =>
          confirmSingleCharacter(projectId, char.charId).catch(() => { /* 单个失败不阻断 */ })
        )
      );
      loadCharacters();
    } finally {
      setBatchLoading(false);
    }
  };

  const handleLockAll = async () => {
    if (!projectId) return;
    const reviewable = characters.filter(c => getCharPhase(c) === 'review');
    if (reviewable.length === 0) return;
    setBatchLoading(true);
    try {
      for (const char of reviewable) {
        try { await lockSingleCharacter(projectId, char.charId); } catch { /* 单个失败不阻断 */ }
      }
      loadCharacters();
    } finally {
      setBatchLoading(false);
    }
  };

  const hasConfiguring = phaseCount.configuring > 0;
  const hasReview = phaseCount.review > 0;
  const allLocked = characters.length > 0 && characters.every(c => getCharPhase(c) === 'locked');

  const [advancing, setAdvancing] = useState(false);

  const handleAdvance = async () => {
    if (!projectId) return;
    setAdvancing(true);
    try {
      await advanceStatus(projectId, 'forward', 'confirm_assets');
    } catch (err: any) {
      alert(err.message || '确认素材失败');
    } finally {
      setAdvancing(false);
    }
  };

  // ========== 主渲染 ==========
  const isFailed = statusInfo?.isFailed ?? false;

  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>角色与素材</h1>
        <p className={styles.subtitle}>共 {characters.length} 个角色，点击角色卡片管理配置与素材</p>
      </div>

      {/* 进度统计 */}
      {characters.length > 0 && (
        <div className={styles.phaseProgress}>
          <span>配置中 {phaseCount.configuring}</span>
          <span>生成中 {phaseCount.generating}</span>
          <span>待审核 {phaseCount.review}</span>
          <span>已锁定 {phaseCount.locked}</span>
        </div>
      )}

      {isLoadingStatus ? (
        <div className={styles.loadingState}>
          <div className={styles.spinner}></div>
          <p>正在加载数据...</p>
        </div>
      ) : isFailed ? (
        <div className={styles.errorState}>
          <p>{statusInfo?.errorMessage || statusInfo?.statusDescription || '操作失败'}</p>
          <button className={styles.retryButton} onClick={() => { if (projectId) syncStatus(projectId); }}>重试</button>
        </div>
      ) : error && !characters.length ? (
        <div className={styles.errorState}>
          <p>{error}</p>
          <button className={styles.retryButton} onClick={() => { setError(''); loadCharacters(); }}>重试</button>
        </div>
      ) : isExtracting ? (
        <div className={styles.loadingState}>
          <div className={styles.spinner}></div>
          <p>正在从剧本中提取角色...</p>
        </div>
      ) : (
        <>
          {characters.length > 0 && (
            <div className={styles.characterGrid}>{characters.map(c => renderCard(c))}</div>
          )}

          <div className={styles.bottomActions}>
            {hasConfiguring && (
              <button className={styles.startButton} onClick={handleGenerateAll} disabled={batchLoading}>
                {batchLoading ? '生成中...' : '一键生成全部素材'}
              </button>
            )}
            {hasReview && (
              <button className={styles.confirmButton} onClick={handleLockAll} disabled={batchLoading}>
                {batchLoading ? '锁定中...' : '一键锁定全部'}
              </button>
            )}
            {allLocked && (
              <button className={styles.confirmButton} onClick={handleAdvance} disabled={advancing}>
                {advancing ? '确认中...' : '确认素材，进入下一步'}
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
};

export default Step3Merged;
