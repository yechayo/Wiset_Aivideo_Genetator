/**
 * 剧本生成加载遮罩层
 * 显示 AI 正在生成剧本的加载状态
 */

import styles from './ScriptGeneratingOverlay.module.less';

interface ScriptGeneratingOverlayProps {
  isVisible: boolean;
  message?: string;
}

// 加载动画图标
function LoaderIcon() {
  return (
    <div className={styles.loader}>
      <div className={styles.spinner}></div>
      <div className={styles.glow}></div>
    </div>
  );
}

export default function ScriptGeneratingOverlay({
  isVisible,
  message = 'AI 正在生成剧本，请稍候...'
}: ScriptGeneratingOverlayProps) {
  if (!isVisible) return null;

  return (
    <div className={styles.overlay}>
      <div className={styles.content}>
        <LoaderIcon />

        <div className={styles.messageContainer}>
          <h2 className={styles.title}>剧本生成中</h2>
          <p className={styles.message}>{message}</p>
        </div>

        <div className={styles.tips}>
          <p className={styles.tipText}>
            此过程可能需要几秒到几分钟，取决于故事复杂度
          </p>
        </div>
      </div>
    </div>
  );
}
