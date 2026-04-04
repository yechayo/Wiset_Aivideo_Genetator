import Select, { type SelectOption } from './Select';
import styles from './VoiceSelect.module.less';

interface VoiceSelectProps {
  options: SelectOption[];
  value: string;
  onChange: (voiceId: string) => void;
  placeholder?: string;
  onPreview: (voiceId: string) => void;
  previewLoading?: boolean;
  previewVoiceId?: string | null;
}

export default function VoiceSelect({
  options,
  value,
  onChange,
  placeholder = '选择音色',
  onPreview,
  previewLoading = false,
  previewVoiceId = null,
}: VoiceSelectProps) {
  return (
    <Select
      options={options}
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      columns={2}
      optionRender={(option, isSelected) => {
        const isPlaying = previewVoiceId === option.value;
        return (
          <>
            <span className={`${styles.name} ${isSelected ? styles.selected : ''}`}>
              {option.label}
            </span>
            <button
              type="button"
              className={`${styles.previewBtn} ${isPlaying ? styles.playing : ''}`}
              onClick={(e) => {
                e.stopPropagation();
                onPreview(option.value);
              }}
              disabled={previewLoading}
            >
              {previewLoading && !previewVoiceId ? '...' : isPlaying ? '■' : '▶'}
            </button>
          </>
        );
      }}
    />
  );
}
