import { useState, useCallback } from 'react';
import { ToastContext } from './ToastContext';
import type { Toast } from './ToastContext';

interface ToastProviderProps {
  children: React.ReactNode;
}

// 退出动画持续时间（与 CSS 动画时间一致）
const EXIT_ANIMATION_DURATION = 300;

export function ToastProvider({ children }: ToastProviderProps) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [exitingIds, setExitingIds] = useState<string[]>([]);

  // 开始退出动画（先播放动画，再真正移除）
  const startRemove = useCallback((id: string) => {
    // 添加到退出中列表
    setExitingIds((prev) => [...prev, id]);

    // 等待动画完成后真正移除
    setTimeout(() => {
      setToasts((prev) => prev.filter((toast) => toast.id !== id));
      setExitingIds((prev) => prev.filter((exitId) => exitId !== id));
    }, EXIT_ANIMATION_DURATION);
  }, []);

  // 立即移除（不播放动画）
  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
    setExitingIds((prev) => prev.filter((exitId) => exitId !== id));
  }, []);

  const addToast = useCallback((toast: Omit<Toast, 'id'>) => {
    const id = `toast-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`;
    const newToast: Toast = { ...toast, id };
    setToasts((prev) => [...prev, newToast]);

    // 自动关闭逻辑 - 使用 startRemove 播放退出动画
    if (!toast.persistent && toast.duration !== 0) {
      const duration = toast.duration ?? 3000;
      setTimeout(() => {
        startRemove(id);
      }, duration);
    }
  }, [startRemove]);

  return (
    <ToastContext.Provider value={{ toasts, exitingIds, addToast, startRemove, removeToast }}>
      {children}
    </ToastContext.Provider>
  );
}
