import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import styles from './ProjectDetailPage.module.less';
import { ArrowLeftIcon, EditIcon } from '../../components/icons/Icons';
import { getProject, getProjectStatus } from '../../services/projectService';
import type { Project, ProjectStatusInfo } from '../../services/types/project.types';

const pi = (p?: Project) => p?.projectInfo;

// 类型中文映射
const genreMap: Record<string, string> = {
  '2d-anime': '2D 动漫',
  '3d-realistic': '3D 写实',
  'ink-chinese': '水墨国风',
};

// 受众中文映射
const audienceMap: Record<string, string> = {
  children: '儿童',
  teen: '青少年',
  'young-adult': '青年',
  adult: '成人',
};

// 格式化日期
function formatDate(dateString?: string): string {
  if (!dateString) return '-';
  const date = new Date(dateString);
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function ProjectDetailPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [project, setProject] = useState<Project | null>(null);
  const [statusInfo, setStatusInfo] = useState<ProjectStatusInfo | null>(null);
  const [loading, setLoading] = useState(true);

  // 获取项目详情和状态
  useEffect(() => {
    async function fetchProject() {
      if (!projectId) return;
      try {
        const [projectRes, statusRes] = await Promise.all([
          getProject(projectId),
          getProjectStatus(projectId),
        ]);
        if ((projectRes.code === 0 || projectRes.code === 200) && projectRes.data) {
          setProject(projectRes.data);
        }
        if ((statusRes.code === 0 || statusRes.code === 200) && statusRes.data) {
          setStatusInfo(statusRes.data);
        }
      } catch (error) {
        console.error('获取项目详情失败:', error);
      } finally {
        setLoading(false);
      }
    }
    fetchProject();
  }, [projectId]);

  if (loading) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.loadingState}>
          <div className={styles.loadingSpinner} />
          <span>加载中...</span>
        </div>
      </div>
    );
  }

  if (!project) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.emptyState}>
          <h2>项目不存在</h2>
          <p>该项目可能已被删除或您没有访问权限</p>
          <button onClick={() => navigate('/projects')} className={styles.backButton}>
            返回项目列表
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.pageContainer}>
      {/* 头部 */}
      <div className={styles.header}>
        <button onClick={() => navigate('/projects')} className={styles.backButton}>
          <ArrowLeftIcon />
          <span>返回项目列表</span>
        </button>
        <button
          onClick={() => {
            navigate(`/project/${project.projectId}/step`);
          }}
          className={styles.editButton}
        >
          <EditIcon />
          <span>编辑项目</span>
        </button>
      </div>

      {/* 项目信息卡片 */}
      <div className={styles.projectCard}>
        <div className={styles.cardHeader}>
          <div className={styles.titleSection}>
            <h1 className={styles.projectTitle}>项目详情</h1>
            <span
              className={`${styles.projectStatus} ${
                statusInfo?.isFailed ? styles.statusFailed
                  : statusInfo?.isGenerating ? styles.statusGenerating
                  : statusInfo?.isReview ? styles.statusReview
                  : statusInfo?.statusCode === 'COMPLETED' ? styles.statusCompleted
                  : styles.statusDraft
              }`}
            >
              {statusInfo?.statusDescription || '草稿'}
            </span>
          </div>
          <div className={styles.projectId}>ID: {project.projectId}</div>
        </div>

        {/* 基本信息 */}
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>基本信息</h2>
          <div className={styles.infoGrid}>
            <div className={styles.infoItem}>
              <span className={styles.infoLabel}>故事大纲</span>
              <span className={styles.infoValue}>{pi(project)?.storyPrompt}</span>
            </div>
            <div className={styles.infoItem}>
              <span className={styles.infoLabel}>类型风格</span>
              <span className={styles.infoValue}>
                {genreMap[pi(project)?.genre || ''] || pi(project)?.genre || '未分类'}
              </span>
            </div>
            <div className={styles.infoItem}>
              <span className={styles.infoLabel}>目标受众</span>
              <span className={styles.infoValue}>
                {audienceMap[pi(project)?.targetAudience || ''] || pi(project)?.targetAudience}
              </span>
            </div>
            <div className={styles.infoItem}>
              <span className={styles.infoLabel}>总集数</span>
              <span className={styles.infoValue}>{pi(project)?.totalEpisodes || 0} 集</span>
            </div>
            <div className={styles.infoItem}>
              <span className={styles.infoLabel}>单集时长</span>
              <span className={styles.infoValue}>{pi(project)?.episodeDuration || 0} 秒</span>
            </div>
            <div className={styles.infoItem}>
              <span className={styles.infoLabel}>每章集数</span>
              <span className={styles.infoValue}>{pi(project)?.episodesPerChapter || 4} 集</span>
            </div>
          </div>
        </div>

        {/* 时间信息 */}
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>时间信息</h2>
          <div className={styles.timeInfo}>
            <div className={styles.timeItem}>
              <span className={styles.timeLabel}>创建时间</span>
              <span className={styles.timeValue}>{formatDate(project.createdAt)}</span>
            </div>
            <div className={styles.timeItem}>
              <span className={styles.timeLabel}>更新时间</span>
              <span className={styles.timeValue}>{formatDate(project.updatedAt)}</span>
            </div>
          </div>
        </div>

        {/* 剧本大纲 */}
        {pi(project)?.script?.outline && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>剧本大纲</h2>
            <div className={styles.outlineContent}>
              <pre>{pi(project)!.script!.outline}</pre>
            </div>
          </div>
        )}

        {/* 当前选中章节 */}
        {pi(project)?.selectedChapter && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>当前章节</h2>
            <div className={styles.selectedChapter}>
              {pi(project)!.selectedChapter}
            </div>
          </div>
        )}

        {/* 状态详情 */}
        {statusInfo && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>状态详情</h2>
            <div className={styles.infoGrid}>
              <div className={styles.infoItem}>
                <span className={styles.infoLabel}>状态码</span>
                <span className={styles.infoValue}>{statusInfo.statusCode}</span>
              </div>
              <div className={styles.infoItem}>
                <span className={styles.infoLabel}>当前步骤</span>
                <span className={styles.infoValue}>第 {statusInfo.currentStep} 步</span>
              </div>
              {statusInfo.completedSteps.length > 0 && (
                <div className={styles.infoItem}>
                  <span className={styles.infoLabel}>已完成步骤</span>
                  <span className={styles.infoValue}>{statusInfo.completedSteps.join(', ')}</span>
                </div>
              )}
              {statusInfo.availableActions.length > 0 && (
                <div className={styles.infoItem}>
                  <span className={styles.infoLabel}>可用操作</span>
                  <span className={styles.infoValue}>{statusInfo.availableActions.join(', ')}</span>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export default ProjectDetailPage;
