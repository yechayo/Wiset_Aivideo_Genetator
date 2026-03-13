import { createContext } from 'react';

export type ToastType = 'success' | 'error' | 'warning' | 'info';

export interface Toast {
  id: string;
  type: ToastType;
  message: string;
  duration?: number;
  persistent?: boolean;
}

export interface ToastContextValue {
  toasts: Toast[];
  exitingIds: string[];
  addToast: (toast: Omit<Toast, 'id'>) => void;
  startRemove: (id: string) => void;
  removeToast: (id: string) => void;
}

export const ToastContext = createContext<ToastContextValue | undefined>(undefined);
