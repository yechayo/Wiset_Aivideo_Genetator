import { useEffect, useState, useCallback, useRef } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import styles from './Step3page.module.less';
import type { Project, CharacterListItem } from '../../../services';
import { getCharacters, isApiSuccess } from '../../../services';
import type { StepContentProps } from '../types';
import { useCreateStore } from '../../../stores/createStore';
import {
  extractCharacters,
  updateCharacter,
  deleteCharacter,
  confirmCharacters,
  setVisualStyle,
} from '../../../services/characterService';
import { UsersIcon } from '../../../components/icons/Icons';

interface Step3pageProps extends StepContentProps {
  project: Project;
}

/** 角色在组件内的合并数据 */
interface CharacterItem {
  char: CharacterListItem;
}

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

// 物种类型选项
const SPECIES_OPTIONS = [
  { value: 'HUMAN', label: '人类' },
  { value: 'ANTHRO_ANIMAL', label: '拟人化动物' },
  { value: 'CREATURE', label: '奇幻/科幻种族' },
  { value: 'ANIMAL', label: '真实动物' },
];

const Step3page = ({ project }: Step3pageProps) => {
  const { statusInfo, canPerformAction, isLoadingStatus } = useCreateStore();
  const projectId = project.projectId!;
  const prefersReducedMotion = useReducedMotion();

  // 角色数据
  const [characters, setCharacters] = useState<CharacterItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // 展开/编辑状态
  const [expandedCharId, setExpandedCharId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<Partial<CharacterListItem>>({});
  const [saving, setSaving] = useState(false);
  const [confirming, setConfirming] = useState(false);

  // 数据轮询相关状态
  const maxDataPollingCount = 45; // 覆盖 AI 生成耗时

  // 判断是否可提取角色
  const canExtract = canPerformAction('extract_characters');
  const isFailed = statusInfo?.isFailed;

  // 加载角色列表
  const fetchCharacters = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    setError('');
    try {
      const res = await getCharacters(projectId);
      if (isApiSuccess(res) && res.data) {
        const items: CharacterItem[] = res.data.items.map(char => ({ char }));
        setCharacters(items);
      }
    } catch (err: any) {
      setError(err.message || '获取角色列表失败');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  // 监听后端生成状态：isGenerating 从 true → false 时重新拉取
  const prevGeneratingRef = useRef(statusInfo?.isGenerating ?? false);
  useEffect(() => {
    const was = prevGeneratingRef.current;
    const now = statusInfo?.isGenerating ?? false;
    prevGeneratingRef.current = now;
    if (was && !now && characters.length === 0) {
      fetchCharacters();
    }
  }, [statusInfo?.isGenerating, characters.length, fetchCharacters]);

  // 监听状态码变化：进入 CHARACTER_REVIEW 时重新加载角色
  const prevStatusCodeRef = useRef(statusInfo?.statusCode ?? '');
  useEffect(() => {
    const prev = prevStatusCodeRef.current;
    const curr = statusInfo?.statusCode ?? '';
    prevStatusCodeRef.current = curr;

    // 当从非 CHARACTER_REVIEW 变为 CHARACTER_REVIEW 时，重新加载角色
    if (prev !== 'CHARACTER_REVIEW' && curr === 'CHARACTER_REVIEW') {
      fetchCharacters();
    }
  }, [statusInfo?.statusCode, fetchCharacters]);

  // 进入角色审核状态时加载角色
  useEffect(() => {
    if (!projectId) return;

    const loadDataWithPolling = async (attempt: number = 0) => {
      setLoading(true);
      try {
        const res = await getCharacters(projectId);
        if (isApiSuccess(res) && res.data && res.data.items.length > 0) {
          const items: CharacterItem[] = res.data.items.map(char => ({ char }));
          setCharacters(items);
          setLoading(false);
        } else {
          if (attempt < maxDataPollingCount) {
            setTimeout(() => {
              loadDataWithPolling(attempt + 1);
            }, 2000);
          } else {
            setCharacters([]);
            setLoading(false);
          }
        }
      } catch (err: any) {
        if (attempt < maxDataPollingCount) {
          setTimeout(() => {
            loadDataWithPolling(attempt + 1);
          }, 2000);
        } else {
          setError(err.message || '获取角色列表失败');
          setLoading(false);
        }
      }
    };

    loadDataWithPolling();
  }, [projectId]);

  // 提取角色
  const handleExtract = async () => {
    if (!projectId) return;
    setLoading(true);
    setError('');
    try {
      await extractCharacters(projectId);
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
      const char = characters.find(c => c.char.charId === charId);
      if (char) {
        setExpandedCharId(charId);
        setEditForm({
          name: char.char.name,
          role: char.char.role,
          personality: char.char.personality,
          appearance: char.char.appearance,
          voice: char.char.voice,
          background: char.char.background,
          species: char.char.species,
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
    if (!projectId) return;
    setSaving(true);
    try {
      await updateCharacter(projectId, charId, editForm);
      await fetchCharacters();
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
    const confirmed = window.confirm('确定要删除该角色吗？删除后不可恢复。');
    if (!confirmed) return;
    try {
      await deleteCharacter(projectId, charId);
      if (expandedCharId === charId) {
        setExpandedCharId(null);
        setEditForm({});
      }
      await fetchCharacters();
    } catch (err: any) {
      alert(err.message || '删除失败');
    }
  };

  // 设置视觉风格
  const handleStyleChange = async (charId: string, style: string) => {
    setCharacters(prev => prev.map(item => {
      if (item.char.charId !== charId) return item;
      return {
        ...item,
        char: { ...item.char, visualStyle: style },
      };
    }));
    try {
      await setVisualStyle(projectId, charId, style);
    } catch {
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
    } catch (err: any) {
      alert(err.message || '确认失败');
    } finally {
      setConfirming(false);
    }
  };

  const getRoleClass = (role: string) => {
    switch (role) {
      case '主角': return styles.protagonist;
      case '反派': return styles.antagonist;
      default: return styles.supporting;
    }
  };

  // ========== 渲染 ==========
  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>角色配置</h1>
        <p className={styles.subtitle}>
          共 {characters.length} 个角色，点击角色卡片编辑详情
        </p>
      </div>

      {isLoadingStatus || loading ? (
        <div className={styles.loadingState}>
          <div className={styles.spinner}></div>
          <p>正在加载角色数据...</p>
        </div>
      ) : error ? (
        <div className={styles.errorState}>
          <p>{error}</p>
          <button className={styles.retryButton} onClick={fetchCharacters}>
            重试
          </button>
        </div>
      ) : canExtract ? (
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
      ) : isFailed ? (
        <div className={styles.errorSection}>
          <p>{error || statusInfo?.statusDescription || '角色提取失败'}</p>
          <button className={styles.retryButton} onClick={handleExtract} aria-label="重新提取角色">
            重新提取
          </button>
        </div>
      ) : characters.length === 0 ? (
        <div className={styles.emptyState}>
          <p>暂无角色数据</p>
          <p className={styles.emptyHint}>角色可能还在提取中，请稍后刷新</p>
        </div>
      ) : (
        <>
          <div className={styles.characterGrid}>
            {characters.map(char => {
              const isExpanded = expandedCharId === char.char.charId;
              const st = char.char;

              return (
                <div
                  key={char.char.charId}
                  className={`${styles.characterCard} ${isExpanded ? styles.expanded : ''}`}
                  role={isExpanded ? undefined : 'button'}
                  tabIndex={isExpanded ? -1 : 0}
                  aria-expanded={isExpanded}
                  aria-label={`${char.char.name} - ${char.char.role}`}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleExpand(char.char.charId); } }}
                >
                  <div className={styles.cardHeader} onClick={() => handleExpand(char.char.charId)}>
                    <div className={styles.cardAvatar}>
                      <span>{char.char.name.charAt(0)}</span>
                    </div>
                    <div className={styles.cardInfo}>
                      <h3 className={styles.cardName}>{char.char.name}</h3>
                      <span className={`${styles.cardRole} ${getRoleClass(char.char.role)}`}>
                        {char.char.role}
                      </span>
                    </div>
                  </div>

                  <AnimatePresence initial={false}>
                    {isExpanded && (
                      <motion.div
                        className={styles.expandedContent}
                        initial={{ opacity: prefersReducedMotion ? 1 : 0, y: prefersReducedMotion ? 0 : -8 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: prefersReducedMotion ? 1 : 0, y: prefersReducedMotion ? 0 : -8 }}
                        transition={{ duration: prefersReducedMotion ? 0 : 0.25, ease: [0.4, 0, 0.2, 1] }}
                      >
                        <div className={styles.editHeader}>
                          <h4 className={styles.editTitle}>角色详情</h4>
                          <div className={styles.editHeaderActions}>
                            <button
                              className={styles.deleteBtn}
                              onClick={() => handleDelete(char.char.charId)}
                              aria-label="删除角色"
                            >
                              删除角色
                            </button>
                            <button
                              className={styles.collapseBtn}
                              onClick={() => { setExpandedCharId(null); setEditForm({}); }}
                              aria-label="收起角色详情"
                            >
                              收起
                            </button>
                          </div>
                        </div>

                        <div className={styles.expandedLeft}>
                          <div className={styles.editForm}>
                            <div className={styles.formGroup}>
                              <label className={styles.formLabel} htmlFor={`name-${char.char.charId}`}>角色名称</label>
                              <input
                                id={`name-${char.char.charId}`}
                                className={styles.formInput}
                                value={editForm.name || ''}
                                onChange={(e) => handleFormChange('name', e.target.value)}
                                placeholder="角色名称"
                              />
                            </div>
                            <div className={styles.formGroup}>
                              <label className={styles.formLabel} htmlFor={`role-${char.char.charId}`}>角色定位</label>
                              <select
                                id={`role-${char.char.charId}`}
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
                              <label className={styles.formLabel} htmlFor={`style-${char.char.charId}`}>视觉风格</label>
                              <select
                                id={`style-${char.char.charId}`}
                                className={styles.formSelect}
                                value={st?.visualStyle || project.projectInfo?.visualStyle || project.projectInfo?.genre || '3D'}
                                onChange={(e) => handleStyleChange(char.char.charId, e.target.value)}
                              >
                                {VISUAL_STYLE_OPTIONS.map(opt => (
                                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                                ))}
                              </select>
                            </div>
                            <div className={styles.formGroup}>
                              <label className={styles.formLabel} htmlFor={`species-${char.char.charId}`}>物种类型</label>
                              <select
                                id={`species-${char.char.charId}`}
                                className={styles.formSelect}
                                value={editForm.species || 'HUMAN'}
                                onChange={(e) => handleFormChange('species', e.target.value)}
                              >
                                {SPECIES_OPTIONS.map(opt => (
                                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                                ))}
                              </select>
                            </div>
                            <div className={styles.formGroup}>
                              <label className={styles.formLabel} htmlFor={`personality-${char.char.charId}`}>性格描述</label>
                              <input
                                id={`personality-${char.char.charId}`}
                                className={styles.formInput}
                                value={editForm.personality || ''}
                                onChange={(e) => handleFormChange('personality', e.target.value)}
                                placeholder="角色性格"
                              />
                            </div>
                            <div className={`${styles.formGroup} ${styles.fullWidth}`}>
                              <label className={styles.formLabel} htmlFor={`appearance-${char.char.charId}`}>外貌描述</label>
                              <textarea
                                id={`appearance-${char.char.charId}`}
                                className={styles.formTextarea}
                                value={editForm.appearance || ''}
                                onChange={(e) => handleFormChange('appearance', e.target.value)}
                                placeholder="角色外貌特征"
                              />
                            </div>
                            <div className={`${styles.formGroup} ${styles.fullWidth}`}>
                              <label className={styles.formLabel} htmlFor={`voice-${char.char.charId}`}>声音描述</label>
                              <textarea
                                id={`voice-${char.char.charId}`}
                                className={styles.formTextarea}
                                value={editForm.voice || ''}
                                onChange={(e) => handleFormChange('voice', e.target.value)}
                                placeholder="角色声音特征"
                              />
                            </div>
                            <div className={`${styles.formGroup} ${styles.fullWidth}`}>
                              <label className={styles.formLabel} htmlFor={`background-${char.char.charId}`}>背景故事</label>
                              <textarea
                                id={`background-${char.char.charId}`}
                                className={styles.formTextarea}
                                value={editForm.background || ''}
                                onChange={(e) => handleFormChange('background', e.target.value)}
                                placeholder="角色背景故事"
                              />
                            </div>
                          </div>

                          <button
                            className={styles.saveBtn}
                            onClick={() => handleSave(char.char.charId)}
                            disabled={saving}
                            aria-label="保存角色修改"
                          >
                            {saving ? '保存中...' : '保存修改'}
                          </button>
                        </div>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
              );
            })}
          </div>

          <div className={styles.bottomActions}>
            <button
              className={styles.confirmButton}
              onClick={handleConfirm}
              disabled={confirming}
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
