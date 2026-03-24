import styles from './PanelInfoCard.module.less';

interface PanelInfoCardProps {
  scene: string;
  shotSize: string;
  cameraAngle: string;
  characters: string;
  dialogue: string;
}

export default function PanelInfoCard({ scene, shotSize, cameraAngle, characters, dialogue }: PanelInfoCardProps) {
  return (
    <div className={styles.card}>
      <div className={styles.row}>
        {shotSize && <span className={styles.tag}>{shotSize}</span>}
        {cameraAngle && <span className={styles.tag}>{cameraAngle}</span>}
      </div>
      {scene && <p className={styles.scene}>{scene}</p>}
      {characters && <p className={styles.field}><span className={styles.label}>角色: </span>{characters}</p>}
      {dialogue && <p className={styles.field}><span className={styles.label}>对话: </span>"{dialogue}"</p>}
    </div>
  );
}
