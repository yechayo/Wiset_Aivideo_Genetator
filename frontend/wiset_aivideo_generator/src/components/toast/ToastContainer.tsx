import { useContext } from 'react';
import { ToastContext } from './ToastContext';
import { ToastItem } from './Toast';
import styles from './Toast.module.less';

type ToastPosition =
  | 'top-right'
  | 'top-center'
  | 'top-left'
  | 'bottom-right'
  | 'bottom-center'
  | 'bottom-left';

interface ToastContainerProps {
  position?: ToastPosition;
  limit?: number; // 最多显示的 Toast 数量
}

export function ToastContainer({
  position = 'top-right',
  limit = 5,
}: ToastContainerProps) {
  const context = useContext(ToastContext);

  if (!context) {
    console.warn('ToastContainer must be used within ToastProvider');
    return null;
  }

  const { toasts, exitingIds, startRemove } = context;

  // 限制显示数量，显示最新的
  const visibleToasts = toasts.slice(-limit);

  if (visibleToasts.length === 0) {
    return null;
  }

  // 位置样式映射
  const positionClassMap: Record<ToastPosition, string> = {
    'top-right': styles.topRight,
    'top-center': styles.topCenter,
    'top-left': styles.topLeft,
    'bottom-right': styles.bottomRight,
    'bottom-center': styles.bottomCenter,
    'bottom-left': styles.bottomLeft,
  };

  return (
    <div className={`${styles.container} ${positionClassMap[position]}`}>
      {visibleToasts.map((toast) => (
        <ToastItem
          key={toast.id}
          toast={toast}
          isExiting={exitingIds.includes(toast.id)}
          onStartRemove={startRemove}
        />
      ))}
    </div>
  );
}
