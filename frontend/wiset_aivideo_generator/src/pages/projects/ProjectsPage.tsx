import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import styles from './ProjectsPage.module.less';
import { PlusIcon } from '../../components/icons/Icons';
import { getProjects } from '../../services/projectService';
import type { ProjectListItem } from '../../services/types/project.types';

// 根据状态类型返回样式类
function getStatusClass(item: ProjectListItem): string {
  if (item.isFailed) return styles.statusFailed;
  if (item.isGenerating) return styles.statusGenerating;
  if (item.isReview) return styles.statusReview;
  if (item.statusCode === 'COMPLETED') return styles.statusCompleted;
  return styles.statusDraft;
}

// 格式化日期
function formatDate(dateString?: string): string {
  if (!dateString) return '';
  const date = new Date(dateString);
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
}

function ProjectsPage() {
  const [projects, setProjects] = useState<ProjectListItem[]>([]);
  const [loading, setLoading] = useState(true);

  // 获取项目列表
  useEffect(() => {
    async function fetchProjects() {
      try {
        const response = await getProjects();
        if ((response.code === 0 || response.code === 200) && response.data) {
          setProjects(response.data.items);
        }
      } catch (error) {
        console.error('获取项目列表失败:', error);
      } finally {
        setLoading(false);
      }
    }
    fetchProjects();
  }, []);

  return (
    <div className={styles.pageContainer}>
      {/* 头部 */}
      <div className={styles.header}>
        <div className={styles.headerContent}>
          <h1>我的项目</h1>
          <p className={styles.subtitle}>管理和编辑你的所有 AI 视频项目</p>
        </div>
        <Link to="/create" className={styles.createButton}>
          <PlusIcon className={styles.plusIcon} />
          <span>新建项目</span>
        </Link>
      </div>

      {/* 项目列表 */}
      {loading ? (
        <div className={styles.loadingState}>
          <div className={styles.loadingSpinner} />
          <span>加载中...</span>
        </div>
      ) : projects.length > 0 ? (
        <div className={styles.projectGrid}>
          {projects.map((project) => (
            <Link
              key={project.projectId}
              to={`/projects/${project.projectId}`}
              className={styles.projectCard}
            >
              <div className={styles.projectHeader}>
                <h3 className={styles.projectTitle}>
                  {project.storyPrompt.length > 50
                    ? project.storyPrompt.slice(0, 50) + '...'
                    : project.storyPrompt}
                </h3>
                <span
                  className={`${styles.projectStatus} ${getStatusClass(project)}`}
                >
                  {project.statusDescription}
                </span>
              </div>

              <div className={styles.projectMeta}>
                <div className={styles.metaItem}>
                  <span className={styles.metaLabel}>画面风格</span>
                  <span className={styles.metaValue}>{project.visualStyle || project.genre || '3D'}</span>
                </div>
                <div className={styles.metaItem}>
                  <span className={styles.metaLabel}>集数</span>
                  <span className={styles.metaValue}>{project.totalEpisodes} 集</span>
                </div>
                <div className={styles.metaItem}>
                  <span className={styles.metaLabel}>单集时长</span>
                  <span className={styles.metaValue}>{project.episodeDuration} 秒</span>
                </div>
                <div className={styles.metaItem}>
                  <span className={styles.metaLabel}>当前步骤</span>
                  <span className={styles.metaValue}>第 {project.currentStep} 步</span>
                </div>
              </div>

              <div className={styles.projectInfo}>
                <span className={styles.targetAudience}>
                  目标受众：{project.targetAudience}
                </span>
              </div>

              <div className={styles.projectFooter}>
                <span className={styles.projectDate}>
                  更新于 {formatDate(project.updatedAt || project.createdAt)}
                </span>
                <span className={styles.projectArrow}>
                  <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path
                      d="M5 12h14M12 5l7 7-7 7"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                </span>
              </div>
            </Link>
          ))}
        </div>
      ) : (
        /* 空状态 */
        <div className={styles.emptyState}>
          <div className={styles.emptyIcon}>
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path
                d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </div>
          <h3 className={styles.emptyTitle}>还没有项目</h3>
          <p className={styles.emptyDescription}>创建你的第一个 AI 视频项目，开始创作之旅</p>
          <Link to="/create" className={styles.emptyButton}>
            <PlusIcon />
            <span>创建新项目</span>
          </Link>
        </div>
      )}
    </div>
  );
}

export default ProjectsPage;
