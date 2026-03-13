import { useContext } from 'react';
import { ToastContext } from './ToastContext';
import type { ToastType } from './ToastContext';

interface ToastOptions {
  type?: ToastType;
  message: string;
  duration?: number;
  persistent?: boolean;
  onClose?: () => void;
}

interface UseToastReturn {
  success: (message: string, options?: Partial<Omit<ToastOptions, 'type' | 'message'>>) => void;
  error: (message: string, options?: Partial<Omit<ToastOptions, 'type' | 'message'>>) => void;
  warning: (message: string, options?: Partial<Omit<ToastOptions, 'type' | 'message'>>) => void;
  info: (message: string, options?: Partial<Omit<ToastOptions, 'type' | 'message'>>) => void;
  show: (options: ToastOptions) => void;
  dismiss: (id: string) => void;
  dismissAll: () => void;
}

export function useToast(): UseToastReturn {
  const context = useContext(ToastContext);

  if (!context) {
    throw new Error('useToast must be used within ToastProvider');
  }

  const { addToast, removeToast, toasts } = context;

  const show = ({ type = 'info', message, duration, persistent, onClose }: ToastOptions) => {
    addToast({ type, message, duration, persistent });
    if (onClose) {
      // 如果有回调，在 duration 后调用（如果不是持久显示）
      if (!persistent && duration !== 0) {
        setTimeout(onClose, duration ?? 3000);
      }
    }
  };

  const success = (message: string, options?: Partial<Omit<ToastOptions, 'type' | 'message'>>) => {
    show({ type: 'success', message, ...options });
  };

  const error = (message: string, options?: Partial<Omit<ToastOptions, 'type' | 'message'>>) => {
    show({ type: 'error', message, ...options });
  };

  const warning = (message: string, options?: Partial<Omit<ToastOptions, 'type' | 'message'>>) => {
    show({ type: 'warning', message, ...options });
  };

  const info = (message: string, options?: Partial<Omit<ToastOptions, 'type' | 'message'>>) => {
    show({ type: 'info', message, ...options });
  };

  const dismiss = (id: string) => {
    removeToast(id);
  };

  const dismissAll = () => {
    toasts.forEach((toast) => removeToast(toast.id));
  };

  return {
    success,
    error,
    warning,
    info,
    show,
    dismiss,
    dismissAll,
  };
}
