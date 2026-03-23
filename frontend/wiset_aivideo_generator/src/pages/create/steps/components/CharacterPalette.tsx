import styles from './CharacterPalette.module.less';
import { useFusionStore } from '../../../../stores/fusionStore';
import type { CharacterReferenceInfo } from '../../../../services/types/episode.types';

interface CharacterPaletteProps {
  characters: CharacterReferenceInfo[];
}

const CharacterPalette = ({ characters }: CharacterPaletteProps) => {
  const { selectedCharacterIndex, selectCharacter } = useFusionStore();

  if (characters.length === 0) {
    return (
      <div className={styles.paletteSection}>
        <div className={styles.paletteHeader}>角色参考图</div>
        <p className={styles.emptyHint}>暂无角色参考图</p>
      </div>
    );
  }

  return (
    <div className={styles.paletteSection}>
      <div className={styles.paletteHeader}>角色参考图</div>
      <div className={styles.characterList}>
        {characters.map((char, index) => {
          return (
            <div
              key={char.characterName}
              className={`${styles.characterCard} ${selectedCharacterIndex === index ? styles.selected : ''}`}
              onClick={() => selectCharacter(selectedCharacterIndex === index ? null : index)}
            >
              <div className={styles.characterName}>{char.characterName}</div>
              {char.threeViewGridUrl ? (
                <div className={styles.characterImages}>
                  <div className={styles.characterImageItem}>
                    <img src={char.threeViewGridUrl} alt="三视图" />
                    <span className={styles.imageLabel}>三视图</span>
                  </div>
                </div>
              ) : (
                <p className={styles.emptyHint}>无参考图</p>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default CharacterPalette;
