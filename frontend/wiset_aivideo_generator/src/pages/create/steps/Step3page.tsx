import { useEffect, useState, useRef, useCallback } from 'react';
import styles from './Step3page.module.less';
import type { Project, CharacterDraft, CharacterStatus } from '../../../services';
import type { StepContentProps } from '../types';
import { useCreateStore } from '../../../stores/createStore';
import {
  getCharacters,
  extractCharacters,
  updateCharacter,
  confirmCharacters,
  getCharacterStatus,
  generateAllImages,
  retryGeneration,
  setVisualStyle,
} from '../../../services/characterService';
import {
  UsersIcon,
  ImageIcon,
  SparklesIcon,
  PersonStandingIcon,
} from '../../../components/icons/Icons';

interface Step3pageProps extends StepContentProps {
  project: Project;
}

/** 角色在组件内的合并数据 */
interface CharacterItem {
  draft: CharacterDraft;
  status?: CharacterStatus;
}

const ROLE_OPTIONS = [
  { value: '主角', label: '主角' },
  { value: '反派', label: '反派' },
  { value: '配角', label: '配角' },
];

const VISUAL_STYLE_OPTIONS = [
  { value: '3D', label: '3D' },
  { value: 'REAL', label: '写实' },
  { value: 'ANIME', label: '动漫' },
];

const Step3page = ({ project }: Step3pageProps) => {
  const { statusInfo, canPerformAction } = useCreateStore();
  const projectId = project.projectId;

  // 角色数据
  const [characters, setCharacters] = useState<CharacterItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // 展开/编辑状态
  const [expandedCharId, setExpandedCharId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<Partial<CharacterDraft>>({});
  const [saving, setSaving] = useState(false);

  // 图片生成轮询
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [generatingCharIds, setGeneratingCharIds] = useState<Set<string>>(new Set());
  const [generatingAll, setGeneratingAll] = useState(false);
  const [confirming, setConfirming] = useState(false);

  // 判断是否可提取角色
  const canExtract = canPerformAction('extract_characters');
  // 判断是否在角色审核状态
  const isReview = statusInfo?.statusCode === 'CHARACTER_REVIEW';
  const isFailed = statusInfo?.isFailed;

  // 加载角色列表（含生成状态）
  const fetchCharacters = useCallback(async () => {
    if (!projectId) return;
    try {
      const res = await getCharacters(projectId);
      if (res.code === 200 && res.data) {
        // 同时获取每个角色的生成状态
        const items: CharacterItem[] = await Promise.all(
          res.data.map(async (draft) => {
            try {
              const statusRes = await getCharacterStatus(draft.charId);
              if (statusRes.code === 200 && statusRes.data) {
                return { draft, status: statusRes.data };
              }
            } catch {
              // 忽略单个角色状态获取失败
            }
            return { draft };
          })
        );
        setCharacters(items);

        // 检查是否有角色正在生成中
        const generatingIds = new Set<string>();
        for (const item of items) {
          if (item.status) {
            if (item.status.isGeneratingExpression || item.status.isGeneratingThreeView) {
              generatingIds.add(item.draft.charId);
            }
          }
        }
        setGeneratingCharIds(generatingIds);
      }
    } catch (err: any) {
      setError(err.message || '获取角色列表失败');
    }
  }, [projectId]);

  // 进入角色审核状态时加载角色
  useEffect(() => {
    if (isReview && projectId) {
      setLoading(true);
      fetchCharacters().finally(() => setLoading(false));
    }
  }, [isReview, projectId, fetchCharacters]);

  // 图片生成状态轮询
  useEffect(() => {
    if (generatingCharIds.size > 0) {
      pollingRef.current = setInterval(fetchCharacters, 3000);
    } else {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    }
    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
      }
    };
  }, [generatingCharIds.size, fetchCharacters]);

  // 提取角色
  const handleExtract = async () => {
    if (!projectId) return;
    setLoading(true);
    setError('');
    try {
      const res = await extractCharacters(projectId);
      if (res.code === 200 && res.data) {
        // 角色提取成功，后端会自动流转到 CHARACTER_REVIEW
        // 等 statusInfo 更新后自动加载角色列表
      }
    } catch (err: any) {
      setError(err.message || '角色提取失败');
    } finally {
      setLoading(false);
    }
  };

  // 展开角色卡片
  const handleExpand = (charId: string) => {
    if (expandedCharId === charId) {
      setExpandedCharId(null);
      setEditForm({});
    } else {
      const char = characters.find(c => c.draft.charId === charId);
      if (char) {
        setExpandedCharId(charId);
        setEditForm({
          name: char.draft.name,
          role: char.draft.role,
          personality: char.draft.personality,
          appearance: char.draft.appearance,
          background: char.draft.background,
        });
      }
    }
  };

  // 更新编辑表单
  const handleFormChange = (field: string, value: string) => {
    setEditForm(prev => ({ ...prev, [field]: value }));
  };

  // 保存角色编辑
  const handleSave = async (charId: string) => {
    setSaving(true);
    try {
      await updateCharacter(charId, editForm);
      // 刷新角色列表
      await fetchCharacters();
    } catch (err: any) {
      alert(err.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  // 生成单个角色图片
  const handleGenerateSingle = async (charId: string) => {
    setGeneratingCharIds(prev => new Set(prev).add(charId));
    try {
      await generateAllImages(charId);
      // 启动轮询等待生成完成
    } catch (err: any) {
      alert(err.message || '生成失败');
      setGeneratingCharIds(prev => {
        const next = new Set(prev);
        next.delete(charId);
        return next;
      });
    }
  };

  // 重试生成
  const handleRetry = async (charId: string, type: 'expression' | 'threeView') => {
    setGeneratingCharIds(prev => new Set(prev).add(charId));
    try {
      await retryGeneration(charId, type);
    } catch (err: any) {
      alert(err.message || '重试失败');
      setGeneratingCharIds(prev => {
        const next = new Set(prev);
        next.delete(charId);
        return next;
      });
    }
  };

  // 一键生成全部
  const handleGenerateAll = async () => {
    setGeneratingAll(true);
    const allIds = new Set<string>();
    for (const char of characters) {
      allIds.add(char.draft.charId);
      try {
        await generateAllImages(char.draft.charId);
      } catch {
        // 单个失败不阻断
      }
    }
    setGeneratingCharIds(allIds);
    setGeneratingAll(false);
  };

  // 设置视觉风格
  const handleStyleChange = async (charId: string, style: string) => {
    // 乐观更新本地状态，让下拉框立即响应
    setCharacters(prev => prev.map(item => {
      if (item.draft.charId !== charId) return item;
      return {
        ...item,
        status: { ...item.status!, visualStyle: style },
      };
    }));
    try {
      await setVisualStyle(charId, style);
    } catch {
      // 失败时回滚
      await fetchCharacters();
    }
  };

  // 确认角色
  const handleConfirm = async () => {
    if (!projectId) return;
    const confirmed = window.confirm('确认后将进入下一步，无法再返回修改角色。请确认角色配置无误。');
    if (!confirmed) return;
    setConfirming(true);
    try {
      await confirmCharacters(projectId);
      // 后端状态流转到 CHARACTER_CONFIRMED，路由守卫自动跳转
    } catch (err: any) {
      alert(err.message || '确认失败');
    } finally {
      setConfirming(false);
    }
  };

  // 获取角色生成状态文本
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

  // 判断角色是否正在生成
  const isCharGenerating = (charId: string) => generatingCharIds.has(charId);

  // ========== 渲染 ==========

  // 引导区：可提取角色
  if (canExtract && !isReview) {
    return (
      <div className={styles.content}>
        <div className={styles.header}>
          <h1 className={styles.title}>角色配置</h1>
          <p className={styles.subtitle}>从剧本中自动提取角色，配置角色形象和素材</p>
        </div>
        <div className={styles.guideSection}>
          <div className={styles.guideIcon}>
            <UsersIcon />
          </div>
          <p className={styles.guideText}>
            剧本已确认，接下来将自动从剧本中提取角色信息
          </p>
          <p className={styles.guideHint}>AI 将分析剧本内容，识别所有重要角色</p>
          <button
            className={styles.extractButton}
            onClick={handleExtract}
            disabled={loading}
            aria-label="开始提取角色"
          >
            {loading ? '提取中...' : '开始提取角色'}
          </button>
        </div>
      </div>
    );
  }

  // 错误状态
  if (isFailed && !isReview) {
    return (
      <div className={styles.content}>
        <div className={styles.header}>
          <h1 className={styles.title}>角色配置</h1>
          <p className={styles.subtitle}>配置角色形象</p>
        </div>
        <div className={styles.errorSection}>
          <p>{error || statusInfo?.statusDescription || '角色提取失败'}</p>
          <button className={styles.retryButton} onClick={handleExtract} aria-label="重新提取角色">
            重新提取
          </button>
        </div>
      </div>
    );
  }

  // 角色审核状态
  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>角色配置</h1>
        <p className={styles.subtitle}>
          共 {characters.length} 个角色，点击角色卡片查看详情和生成图片
        </p>
      </div>

      {loading ? (
        <div className={styles.guideSection}>
          <p style={{ color: 'rgba(255,255,255,0.5)' }}>加载角色中...</p>
        </div>
      ) : characters.length === 0 ? (
        <div className={styles.guideSection}>
          <p style={{ color: 'rgba(255,255,255,0.3)' }}>暂无角色数据</p>
        </div>
      ) : (
        <>
          {/* 角色卡片网格 */}
          <div className={styles.characterGrid}>
            {characters.map(char => {
              const isExpanded = expandedCharId === char.draft.charId;
              const generating = isCharGenerating(char.draft.charId);
              const isSupporting = char.draft.role === '配角';
              const st = char.status;

              return (
                <div
                  key={char.draft.charId}
                  className={`${styles.characterCard} ${isExpanded ? styles.expanded : ''}`}
                  role={isExpanded ? undefined : 'button'}
                  tabIndex={isExpanded ? -1 : 0}
                  aria-expanded={isExpanded}
                  aria-label={`${char.draft.name} - ${char.draft.role}`}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleExpand(char.draft.charId); } }}
                >
                  {/* 卡片头部（始终显示） */}
                  <div className={styles.cardHeader} onClick={() => handleExpand(char.draft.charId)}>
                    <div className={styles.cardAvatar}>
                      {st?.standardImageUrl ? (
                        <img src={st.standardImageUrl} alt={char.draft.name} />
                      ) : (
                        <span>{char.draft.name.charAt(0)}</span>
                      )}
                    </div>
                    <div className={styles.cardInfo}>
                      <h3 className={styles.cardName}>{char.draft.name}</h3>
                      <span className={`${styles.cardRole} ${getRoleClass(char.draft.role)}`}>
                        {char.draft.role}
                      </span>
                    </div>
                    <div className={styles.cardActions}>
                      <button
                        className={`${styles.cardActionBtn} ${generating ? styles.generating : ''}`}
                        onClick={(e) => { e.stopPropagation(); handleGenerateSingle(char.draft.charId); }}
                        disabled={generating}
                        aria-label={`生成${char.draft.name}的图片`}
                      >
                        {generating ? (
                          <><span className={styles.miniSpinner} />生成中</>
                        ) : (
                          <><SparklesIcon className={styles.btnIcon} />生成图片</>
                        )}
                      </button>
                    </div>
                  </div>

                  {/* 展开编辑区域 */}
                  {isExpanded && (
                    <div className={styles.expandedContent}>
                      <div className={styles.editHeader}>
                        <h4 className={styles.editTitle}>角色详情</h4>
                        <button
                          className={styles.collapseBtn}
                          onClick={() => { setExpandedCharId(null); setEditForm({}); }}
                          aria-label="收起角色详情"
                        >
                          收起
                        </button>
                      </div>

                      {/* 编辑表单 */}
                      <div className={styles.editForm}>
                        <div className={styles.formGroup}>
                          <label className={styles.formLabel} htmlFor={`name-${char.draft.charId}`}>角色名称</label>
                          <input
                            id={`name-${char.draft.charId}`}
                            className={styles.formInput}
                            value={editForm.name || ''}
                            onChange={(e) => handleFormChange('name', e.target.value)}
                            placeholder="角色名称"
                          />
                        </div>
                        <div className={styles.formGroup}>
                          <label className={styles.formLabel} htmlFor={`role-${char.draft.charId}`}>角色定位</label>
                          <select
                            id={`role-${char.draft.charId}`}
                            className={styles.formSelect}
                            value={editForm.role || ''}
                            onChange={(e) => handleFormChange('role', e.target.value)}
                          >
                            {ROLE_OPTIONS.map(opt => (
                              <option key={opt.value} value={opt.value}>{opt.label}</option>
                            ))}
                          </select>
                        </div>
                        <div className={styles.formGroup}>
                          <label className={styles.formLabel} htmlFor={`style-${char.draft.charId}`}>视觉风格</label>
                          <select
                            id={`style-${char.draft.charId}`}
                            className={styles.formSelect}
                            value={st?.visualStyle || project.genre || '3D'}
                            onChange={(e) => handleStyleChange(char.draft.charId, e.target.value)}
                          >
                            {VISUAL_STYLE_OPTIONS.map(opt => (
                              <option key={opt.value} value={opt.value}>{opt.label}</option>
                            ))}
                          </select>
                        </div>
                        <div className={styles.formGroup}>
                          <label className={styles.formLabel} htmlFor={`personality-${char.draft.charId}`}>性格描述</label>
                          <input
                            id={`personality-${char.draft.charId}`}
                            className={styles.formInput}
                            value={editForm.personality || ''}
                            onChange={(e) => handleFormChange('personality', e.target.value)}
                            placeholder="角色性格"
                          />
                        </div>
                        <div className={`${styles.formGroup} ${styles.fullWidth}`}>
                          <label className={styles.formLabel} htmlFor={`appearance-${char.draft.charId}`}>外貌描述</label>
                          <textarea
                            id={`appearance-${char.draft.charId}`}
                            className={styles.formTextarea}
                            value={editForm.appearance || ''}
                            onChange={(e) => handleFormChange('appearance', e.target.value)}
                            placeholder="角色外貌特征"
                          />
                        </div>
                        <div className={`${styles.formGroup} ${styles.fullWidth}`}>
                          <label className={styles.formLabel} htmlFor={`background-${char.draft.charId}`}>背景故事</label>
                          <textarea
                            id={`background-${char.draft.charId}`}
                            className={styles.formTextarea}
                            value={editForm.background || ''}
                            onChange={(e) => handleFormChange('background', e.target.value)}
                            placeholder="角色背景故事"
                          />
                        </div>
                      </div>

                      {/* 保存按钮 */}
                      <div style={{ marginBottom: 24 }}>
                        <button
                          className={styles.saveBtn}
                          onClick={() => handleSave(char.draft.charId)}
                          disabled={saving}
                          aria-label="保存角色修改"
                        >
                          {saving ? '保存中...' : '保存修改'}
                        </button>
                      </div>

                      {/* 图片生成区域 */}
                      <div className={styles.imageSection}>
                        {/* 九宫格表情（配角不显示） */}
                        {!isSupporting && (
                          <div className={styles.imageCard}>
                            <h5 className={styles.imageCardTitle}>九宫格表情</h5>
                            <div className={styles.imageStatus}>
                              <span className={`${styles.statusDot} ${
                                st?.expressionStatus === 'GENERATING' ? styles.generating :
                                st?.expressionStatus === 'COMPLETED' ? styles.completed :
                                st?.expressionStatus === 'FAILED' ? styles.failed : styles.pending
                              }`} />
                              {getGenStatusText(st?.expressionStatus, '表情')}
                            </div>
                            {st?.expressionError && (
                              <p className={styles.imageError}>{st.expressionError}</p>
                            )}
                            <div className={styles.imagePreview}>
                              {st?.expressionGridUrl ? (
                                <img src={st.expressionGridUrl} alt={`${char.draft.name} 九宫格表情`} />
                              ) : (
                                <div className={styles.imagePlaceholder}>
                                  <ImageIcon />
                                  <span>等待生成</span>
                                </div>
                              )}
                            </div>
                            <div className={styles.imageActions}>
                              {!st?.expressionGridUrl && st?.expressionStatus !== 'GENERATING' && (
                                <button
                                  className={styles.imageGenBtn}
                                  onClick={() => handleGenerateSingle(char.draft.charId)}
                                  disabled={generating}
                                  aria-label="生成九宫格表情"
                                >
                                  {generating ? '生成中...' : '生成'}
                                </button>
                              )}
                              {st?.expressionStatus === 'FAILED' && (
                                <button
                                  className={styles.imageRetryBtn}
                                  onClick={() => handleRetry(char.draft.charId, 'expression')}
                                  aria-label="重试生成九宫格表情"
                                >
                                  重试
                                </button>
                              )}
                            </div>
                          </div>
                        )}

                        {/* 三视图 */}
                        <div className={styles.imageCard}>
                          <h5 className={styles.imageCardTitle}>三视图</h5>
                          <div className={styles.imageStatus}>
                            <span className={`${styles.statusDot} ${
                              st?.threeViewStatus === 'GENERATING' ? styles.generating :
                              st?.threeViewStatus === 'COMPLETED' ? styles.completed :
                              st?.threeViewStatus === 'FAILED' ? styles.failed : styles.pending
                            }`} />
                            {getGenStatusText(st?.threeViewStatus, '三视图')}
                          </div>
                          {st?.threeViewError && (
                            <p className={styles.imageError}>{st.threeViewError}</p>
                          )}
                          <div className={`${styles.imagePreview} ${styles.threeView}`}>
                            {st?.threeViewGridUrl ? (
                              <img src={st.threeViewGridUrl} alt={`${char.draft.name} 三视图`} />
                            ) : (
                              <div className={styles.imagePlaceholder}>
                                <PersonStandingIcon />
                                <span>等待生成</span>
                              </div>
                            )}
                          </div>
                          <div className={styles.imageActions}>
                            {!st?.threeViewGridUrl && st?.threeViewStatus !== 'GENERATING' && (
                              <button
                                className={styles.imageGenBtn}
                                onClick={() => handleGenerateSingle(char.draft.charId)}
                                disabled={generating}
                                aria-label="生成三视图"
                              >
                                {generating ? '生成中...' : '生成'}
                              </button>
                            )}
                            {st?.threeViewStatus === 'FAILED' && (
                              <button
                                className={styles.imageRetryBtn}
                                onClick={() => handleRetry(char.draft.charId, 'threeView')}
                                aria-label="重试生成三视图"
                              >
                                重试
                              </button>
                            )}
                          </div>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {/* 底部操作区 */}
          <div className={styles.bottomActions}>
            <button
              className={styles.generateAllBtn}
              onClick={handleGenerateAll}
              disabled={generatingAll || generatingCharIds.size > 0}
              aria-label="一键生成全部角色图片"
            >
              {generatingAll || generatingCharIds.size > 0 ? (
                <><span className={styles.miniSpinner} />生成中...</>
              ) : (
                <><SparklesIcon className={styles.btnIcon} />一键生成全部角色图片</>
              )}
            </button>
            <button
              className={styles.confirmButton}
              onClick={handleConfirm}
              disabled={confirming || generatingCharIds.size > 0}
              aria-label="确认角色进入下一步"
            >
              {confirming ? '确认中...' : '确认角色，进入下一步'}
            </button>
          </div>
        </>
      )}
    </div>
  );
};

export default Step3page;
