import { useState, useRef, useEffect, type ReactNode } from 'react';
import styles from './Select.module.less';

export interface SelectOption<V extends string = string> {
  value: V;
  label: string;
}

interface SelectProps<V extends string = string> {
  options: SelectOption<V>[];
  value: V | '';
  onChange: (value: V) => void;
  placeholder?: string;
  /** 自定义选项渲染，不传则只显示 label */
  optionRender?: (option: SelectOption<V>, isSelected: boolean) => ReactNode;
  /** 下拉面板列数，默认 1 */
  columns?: 1 | 2;
  /** 最大高度 */
  maxHeight?: number;
  disabled?: boolean;
}

export default function Select<V extends string = string>({
  options,
  value,
  onChange,
  placeholder = '请选择',
  optionRender,
  columns = 1,
  maxHeight = 280,
  disabled = false,
}: SelectProps<V>) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const selected = options.find((o) => o.value === value);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div className={styles.container} ref={containerRef}>
      <button
        type="button"
        className={`${styles.trigger} ${open ? styles.open : ''} ${value ? styles.hasValue : ''} ${disabled ? styles.disabled : ''}`}
        onClick={() => !disabled && setOpen(!open)}
        disabled={disabled}
      >
        <span className={styles.triggerText}>
          {selected ? selected.label : placeholder}
        </span>
        <svg className={styles.arrow} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {open && (
        <div className={styles.dropdown}>
          <div className={`${styles.list} ${columns === 2 ? styles.twoCols : ''}`} style={{ maxHeight }}>
            {options.map((option) => {
              const isSelected = value === option.value;
              if (optionRender) {
                return (
                  <div
                    key={option.value}
                    className={`${styles.option} ${isSelected ? styles.selected : ''}`}
                    onClick={() => {
                      onChange(option.value);
                      setOpen(false);
                    }}
                  >
                    {optionRender(option, isSelected)}
                  </div>
                );
              }
              return (
                <div
                  key={option.value}
                  className={`${styles.option} ${isSelected ? styles.selected : ''}`}
                  onClick={() => {
                    onChange(option.value);
                    setOpen(false);
                  }}
                >
                  <span className={styles.label}>{option.label}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
