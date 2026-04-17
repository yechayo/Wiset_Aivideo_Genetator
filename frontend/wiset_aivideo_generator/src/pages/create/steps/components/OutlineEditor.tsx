import { useState, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import { ChevronDownIcon, ChevronRightIcon } from '../../../../components/icons/Icons';
import styles from './OutlineEditor.module.less';

interface OutlineEditorProps {
  outline: string;
  onSaveDirect?: (content: string) => void;
  onSaveWithAI?: (content: string, revisionNote: string) => void;
  readOnly?: boolean;
  saving?: boolean;
}

/**
 * 大纲编辑器组件
 * 支持编辑和预览 Markdown 格式的剧本大纲
 */
const OutlineEditor = ({ outline, onSaveDirect, onSaveWithAI, readOnly = false, saving = false }: OutlineEditorProps) => {
  const [content, setContent] = useState(outline);
  const [isEditing, setIsEditing] = useState(false);
  const [collapsed, setCollapsed] = useState(readOnly);

  // 同步后端返回的新大纲
  useEffect(() => {
    if (outline && outline !== content && !isEditing) {
      setContent(outline);
    }
  }, [outline, isEditing]);

  const handleDirectSave = () => {
    if (saving) return;
    onSaveDirect?.(content);
  };

  const handleAIRegenerate = () => {
    if (saving) return;
    const revisionNote = window.prompt('请输入修改意见（可选，留空则基于当前内容重新生成）：');
    if (revisionNote === null) return;
    onSaveWithAI?.(content, revisionNote.trim());
    setIsEditing(false);
  };

  const handleCancel = () => {
    setContent(outline);
    setIsEditing(false);
  };

  return (
    <div className={`${styles.outlineEditor} ${collapsed ? styles.collapsed : ''}`}>
      {/* 工具栏 */}
      <div className={styles.toolbar} onClick={() => !isEditing && setCollapsed(!collapsed)}>
        <div className={styles.toolbarLeft}>
          <button className={styles.collapseButton} onClick={(e) => { e.stopPropagation(); setCollapsed(!collapsed); }}>
            {collapsed ? <ChevronRightIcon /> : <ChevronDownIcon />}
          </button>
          <h3 className={styles.title}>剧本大纲</h3>
        </div>
        <div className={styles.toolbarRight} onClick={(e) => e.stopPropagation()}>
          {!readOnly && (
            <>
              {isEditing ? (
                <>
                  <button
                    className={styles.cancelButton}
                    onClick={handleCancel}
                    disabled={saving}
                  >
                    取消
                  </button>
                  <button
                    className={styles.aiButton}
                    onClick={handleAIRegenerate}
                    disabled={saving}
                  >
                    {saving ? '生成中...' : 'AI 重新生成'}
                  </button>
                  <button
                    className={styles.saveButton}
                    onClick={handleDirectSave}
                    disabled={saving}
                  >
                    {saving ? '保存中...' : '保存修改'}
                  </button>
                </>
              ) : (
                !collapsed && (
                  <button
                    className={styles.editButton}
                    onClick={() => setIsEditing(true)}
                    disabled={saving}
                  >
                    修改大纲
                  </button>
                )
              )}
            </>
          )}
        </div>
      </div>

      {/* 内容区域 */}
      {!collapsed && (
        <div className={styles.contentArea} style={{ position: 'relative' }}>
          {saving && (
            <div style={{
              position: 'absolute',
              inset: 0,
              background: 'rgba(0,0,0,0.4)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              zIndex: 10,
              borderRadius: 'inherit',
            }}>
              <div style={{ color: '#fff', fontSize: 14 }}>保存中...</div>
            </div>
          )}
          {isEditing ? (
            <textarea
              className={styles.textarea}
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="输入剧本大纲..."
            />
          ) : (
            <div className={styles.preview}>
              <div className={styles.markdownContent}>
                <ReactMarkdown>{content}</ReactMarkdown>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default OutlineEditor;
