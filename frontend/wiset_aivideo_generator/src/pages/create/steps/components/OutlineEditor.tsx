import { useState } from 'react';
import styles from './OutlineEditor.module.less';

interface OutlineEditorProps {
  outline: string;
  onSave?: (content: string, revisionNote: string) => void;
  readOnly?: boolean;
}

/**
 * 大纲编辑器组件
 * 支持编辑和预览 Markdown 格式的剧本大纲
 */
const OutlineEditor = ({ outline, onSave, readOnly = false }: OutlineEditorProps) => {
  const [content, setContent] = useState(outline);
  const [isEditing, setIsEditing] = useState(false);
  const [isPreview, setIsPreview] = useState(true);

  const handleSave = () => {
    // 弹出对话框让用户输入修改意见
    const revisionNote = window.prompt('请输入修改意见（必填）：');
    if (revisionNote === null) {
      // 用户点击取消
      return;
    }
    if (!revisionNote.trim()) {
      alert('修改意见不能为空');
      return;
    }
    onSave?.(content, revisionNote.trim());
    setIsEditing(false);
  };

  const handleCancel = () => {
    setContent(outline);
    setIsEditing(false);
  };

  const handleEdit = () => {
    setIsEditing(true);
    setIsPreview(false);
  };

  return (
    <div className={styles.outlineEditor}>
      {/* 工具栏 */}
      <div className={styles.toolbar}>
        <div className={styles.toolbarLeft}>
          <h3 className={styles.title}>剧本大纲</h3>
        </div>
        <div className={styles.toolbarRight}>
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
                    className={styles.saveButton}
                    onClick={handleSave}
                  >
                    保存修改
                  </button>
                </>
              ) : (
                <>
                  <button
                    className={`${styles.viewButton} ${!isPreview ? styles.active : ''}`}
                    onClick={() => setIsPreview(false)}
                  >
                    编辑
                  </button>
                  <button
                    className={`${styles.viewButton} ${isPreview ? styles.active : ''}`}
                    onClick={() => setIsPreview(true)}
                  >
                    预览
                  </button>
                  <button
                    className={styles.editButton}
                    onClick={handleEdit}
                  >
                    修改大纲
                  </button>
                </>
              )}
            </>
          )}
        </div>
      </div>

      {/* 内容区域 */}
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
            {isPreview ? (
              <div
                className={styles.markdownContent}
                dangerouslySetInnerHTML={{ __html: renderMarkdown(content) }}
              />
            ) : (
              <textarea
                className={styles.textarea}
                value={content}
                onChange={(e) => setContent(e.target.value)}
                readOnly={readOnly}
              />
            )}
          </div>
        )}
      </div>
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
