import styles from './Toast.module.less';
import type { Toast as ToastType, ToastType as ToastTypeEnum } from './ToastContext';
import {
  CheckCircleIcon,
  XCircleIcon,
  AlertTriangleIcon,
  InfoIcon,
  XIcon,
} from '../icons/Icons';

// 图标映射
const ICON_MAP: Record<ToastTypeEnum, React.ComponentType<{ className?: string }>> = {
  success: CheckCircleIcon,
  error: XCircleIcon,
  warning: AlertTriangleIcon,
  info: InfoIcon,
};

interface ToastItemProps {
  toast: ToastType;
  isExiting: boolean;
  onStartRemove: (id: string) => void;
}

export function ToastItem({ toast, isExiting, onStartRemove }: ToastItemProps) {
  const Icon = ICON_MAP[toast.type];

  const handleRemove = () => {
    onStartRemove(toast.id);
  };

  // 键盘事件处理
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape' || e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handleRemove();
    }
  };

  return (
    <div
      role="alert"
      aria-live="polite"
      aria-atomic="true"
      className={`${styles.toast} ${styles[toast.type]} ${isExiting ? styles.exiting : styles.entering}`}
    >
      {/* 图标 */}
      <div className={styles.icon} aria-hidden="true">
        <Icon />
      </div>

      {/* 内容 */}
      <div className={styles.content}>
        <p className={styles.message}>{toast.message}</p>
      </div>

      {/* 关闭按钮 */}
      <button
        type="button"
        className={styles.closeButton}
        onClick={handleRemove}
        onKeyDown={handleKeyDown}
        aria-label="关闭通知"
      >
        <XIcon />
      </button>
    </div>
  );
}
