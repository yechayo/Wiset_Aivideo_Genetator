import { useEffect, useState } from 'react';
import { useParams, Navigate } from 'react-router-dom';
import CreateLayout from './CreateLayout';
import styles from './ProjectStepLayout.module.less';
import { getProject } from '../../services/projectService';
import { useProjectStore } from '../../stores';

/**
 * 编辑已有项目入口
 *
 * 从 URL 取 projectId，从后端加载项目数据写入 store
 */
const ProjectStepLayout = () => {
  const { projectId } = useParams<{ projectId: string }>();
  const { setCurrentProject } = useProjectStore();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    async function fetchProject() {
      if (!projectId) {
        setError(true);
        setLoading(false);
        return;
      }
      try {
        const response = await getProject(projectId);
        if ((response.code === 0 || response.code === 200) && response.data) {
          setCurrentProject(response.data);
        } else {
          setError(true);
        }
      } catch {
        setError(true);
      } finally {
        setLoading(false);
      }
    }
    fetchProject();
  }, [projectId, setCurrentProject]);

  if (loading) {
    return (
      <div className={styles.loadingWrap}>
        <div className={styles.loadingBox}>
          <div className={styles.spinner} />
          <p className={styles.loadingText}>加载项目中...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return <Navigate to="/projects" replace />;
  }

  return <CreateLayout />;
};

export default ProjectStepLayout;
