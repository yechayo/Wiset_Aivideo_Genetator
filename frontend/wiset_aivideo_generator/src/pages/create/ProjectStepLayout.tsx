import { useEffect, useState } from 'react';
import { useParams, Navigate } from 'react-router-dom';
import CreateLayout from './CreateLayout';
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
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <div style={{ textAlign: 'center' }}>
          <div className="loading-spinner" style={{
            width: 40, height: 40, border: '3px solid #f3f3f3',
            borderTop: '3px solid #1890ff', borderRadius: '50%',
            animation: 'spin 1s linear infinite', margin: '0 auto'
          }} />
          <p style={{ marginTop: 16, color: '#666' }}>加载项目中...</p>
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