/**
 * 剧本生成加载遮罩层
 * 显示 AI 正在生成剧本的加载状态，支持多阶段文案切换
 */

import { useEffect, useState } from 'react';
import styles from './ScriptGeneratingOverlay.module.less';

interface ScriptGeneratingOverlayProps {
  isVisible: boolean;
  message?: string;
  phase?: 'creating' | 'generating' | 'loading';
}

// 各阶段文案
const PHASE_CONFIG = {
  creating: {
    title: '正在创建项目',
    message: 'AI 正在初始化你的创作空间...',
    tip: '马上就好，请稍候',
  },
  generating: {
    title: '剧本生成中',
    message: 'AI 正在根据你的创意撰写剧本大纲，这可能需要几秒到几分钟...',
    tip: '取决于故事复杂度，请耐心等待',
  },
  loading: {
    title: '正在加载剧本',
    message: 'AI 剧本已生成，正在拉取内容...',
    tip: '即将进入编辑界面',
  },
};

// 加载动画图标
function LoaderIcon({ phase }: { phase: ScriptGeneratingOverlayProps['phase'] }) {
  return (
    <div className={styles.loader}>
      <div className={`${styles.spinner} ${phase === 'loading' ? styles.spinnerFast : ''}`}></div>
      <div className={styles.glow}></div>
    </div>
  );
}

// 步骤指示器
function PhaseIndicator({ phase }: { phase: ScriptGeneratingOverlayProps['phase'] }) {
  const phases: Array<ScriptGeneratingOverlayProps['phase']> = ['creating', 'generating', 'loading'];
  const currentIndex = phases.indexOf(phase ?? 'creating');

  return (
    <div className={styles.phaseIndicator}>
      {phases.map((p, i) => (
        <div
          key={p}
          className={`${styles.phaseDot} ${
            i < currentIndex
              ? styles.phaseDotDone
              : i === currentIndex
              ? styles.phaseDotActive
              : styles.phaseDotPending
          }`}
        />
      ))}
    </div>
  );
}

export default function ScriptGeneratingOverlay({
  isVisible,
  message,
  phase = 'generating',
}: ScriptGeneratingOverlayProps) {
  const [visible, setVisible] = useState(false);
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    if (isVisible) {
      setLeaving(false);
      setVisible(true);
    } else if (visible) {
      // 淡出动画
      setLeaving(true);
      const t = setTimeout(() => {
        setVisible(false);
        setLeaving(false);
      }, 350);
      return () => clearTimeout(t);
    }
  }, [isVisible]);

  if (!visible) return null;

  const config = PHASE_CONFIG[phase];

  return (
    <div className={`${styles.overlay} ${leaving ? styles.overlayLeaving : ''}`}>
      <div className={`${styles.content} ${leaving ? styles.contentLeaving : ''}`}>
        <LoaderIcon phase={phase} />

        <PhaseIndicator phase={phase} />

        <div className={styles.messageContainer}>
          <h2 className={styles.title}>{config.title}</h2>
          <p className={styles.message}>{message ?? config.message}</p>
        </div>

        <div className={styles.tips}>
          <p className={styles.tipText}>{config.tip}</p>
        </div>
      </div>
    </div>
  );
}
