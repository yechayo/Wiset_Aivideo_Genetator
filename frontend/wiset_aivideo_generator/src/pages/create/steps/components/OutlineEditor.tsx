import { useState } from 'react';
import { ChevronDownIcon, ChevronRightIcon } from '../../../../components/icons/Icons';
import styles from './OutlineEditor.module.less';

interface OutlineEditorProps {
  outline: string;
  onSaveDirect?: (content: string) => void;
  onSaveWithAI?: (content: string, revisionNote: string) => void;
  readOnly?: boolean;
}

/**
 * 大纲编辑器组件
 * 支持编辑和预览 Markdown 格式的剧本大纲
 */
const OutlineEditor = ({ outline, onSaveDirect, onSaveWithAI, readOnly = false }: OutlineEditorProps) => {
  const [content, setContent] = useState(outline);
  const [isEditing, setIsEditing] = useState(false);
  const [collapsed, setCollapsed] = useState(false);

  const handleDirectSave = () => {
    onSaveDirect?.(content);
    setIsEditing(false);
  };

  const handleAIRegenerate = () => {
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
    <div className={styles.outlineEditor}>
      {/* 工具栏 */}
      <div className={styles.toolbar} onClick={() => !isEditing && setCollapsed(!collapsed)}>
        <div className={styles.toolbarLeft}>
          <button className={styles.collapseButton} onClick={() => setCollapsed(!collapsed)}>
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
                  >
                    取消
                  </button>
                  <button
                    className={styles.aiButton}
                    onClick={handleAIRegenerate}
                  >
                    AI 重新生成
                  </button>
                  <button
                    className={styles.saveButton}
                    onClick={handleDirectSave}
                  >
                    保存修改
                  </button>
                </>
              ) : (
                !collapsed && (
                  <button
                    className={styles.editButton}
                    onClick={() => setIsEditing(true)}
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
        <div className={styles.contentArea}>
          {isEditing ? (
            <textarea
              className={styles.textarea}
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="输入剧本大纲..."
            />
          ) : (
            <div className={styles.preview}>
              <div
                className={styles.markdownContent}
                dangerouslySetInnerHTML={{ __html: renderMarkdown(content) }}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
};

/**
 * 简单的 Markdown 渲染器
 * 将 Markdown 转换为 HTML
 */
function renderMarkdown(markdown: string): string {
  if (!markdown) return '';

  let html = markdown;

  // 标题
  html = html.replace(/^#### (.+)$/gm, '<h4>$1</h4>');
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');

  // 粗体
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');

  // 斜体
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');

  // 分隔线
  html = html.replace(/^----$/gm, '<hr />');

  // 列表
  html = html.replace(/^- (.+)$/gm, '<li>$1</li>');
  html = html.replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>');

  // 换行
  html = html.replace(/\n/g, '<br />');

  return html;
}

export default OutlineEditor;
