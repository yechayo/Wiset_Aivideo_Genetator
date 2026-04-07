import { useEffect, useState } from 'react';
import { useParams, Navigate } from 'react-router-dom';
import CreateLayout from './CreateLayout';
import styles from './ProjectStepLayout.module.less';
import { getProject, getProjectStatus } from '../../services/projectService';
import { useProjectStore } from '../../stores';
import { useCreateStore, normalizeStatusInfo } from '../../stores/createStore';

/**
 * 编辑已有项目入口
 *
 * 从 URL 取 projectId，从后端加载项目数据和状态写入 store。
 * 同时预取项目状态，确保 CreateLayout 的路由守卫在首次渲染时就能拿到 statusInfo，
 * 避免在 statusInfo 加载完成前错误地展示 Step 1（创建项目表单）。
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
        const [projectRes, statusRes] = await Promise.all([
          getProject(projectId),
          getProjectStatus(projectId),
        ]);
        if ((projectRes.code === 0 || projectRes.code === 200) && projectRes.data) {
          setCurrentProject(projectRes.data);
        } else {
          setError(true);
          return;
        }
        // Pre-populate statusInfo so CreateLayout's route guard can redirect immediately
        if ((statusRes.code === 0 || statusRes.code === 200) && statusRes.data) {
          useCreateStore.setState({
            statusInfo: normalizeStatusInfo(statusRes.data),
          });
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
