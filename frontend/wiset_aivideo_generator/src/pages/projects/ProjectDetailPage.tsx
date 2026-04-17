import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { useParams, useNavigate } from 'react-router-dom';
import styles from './ProjectDetailPage.module.less';
import { ArrowLeftIcon, EditIcon } from '../../components/icons/Icons';
import { getProject, getProjectStatus } from '../../services/projectService';
import type { Project, ProjectStatusInfo } from '../../services/types/project.types';

const COLLAPSE_LINES = 2;

const pi = (p?: Project) => p?.projectInfo;

/** 长文本截断展示，点击可展开/收起 */
function CollapsibleText({ text }: { text: string }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <span
      className={`${styles.storyText} ${expanded ? '' : styles.storyCollapsed}`}
      onClick={() => setExpanded(v => !v)}
    >
      {text}
    </span>
  );
}

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
    month: '2-digit',
    day: '2-digit',
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

  const statusClass = statusInfo?.isFailed ? styles.statusFailed
    : statusInfo?.isGenerating ? styles.statusGenerating
    : statusInfo?.isReview ? styles.statusReview
    : statusInfo?.statusCode === 'completed' ? styles.statusCompleted
    : styles.statusDraft;

  return (
    <div className={styles.pageContainer}>
      {/* 头部 */}
      <div className={styles.header}>
        <button onClick={() => navigate('/projects')} className={styles.backButton}>
          <ArrowLeftIcon />
          <span>返回列表</span>
        </button>
        <div className={styles.headerCenter}>
          <h1 className={styles.projectTitle}>项目详情</h1>
          <span className={`${styles.statusBadge} ${statusClass}`}>
            {statusInfo?.statusDescription || '草稿'}
          </span>
          <span className={styles.projectId}>{project.projectId}</span>
        </div>
        <button
          onClick={() => navigate(`/project/${project.projectId}/step`)}
          className={styles.editButton}
        >
          <EditIcon />
          <span>编辑项目</span>
        </button>
      </div>

      {/* 故事大纲 */}
      {pi(project)?.storyPrompt && (
        <div className={styles.storySection}>
          <CollapsibleText text={pi(project)!.storyPrompt!} />
        </div>
      )}

      {/* 信息网格：一行 label: value */}
      <div className={styles.metaGrid}>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>类型</span>
          <span className={styles.metaValue}>
            {genreMap[pi(project)?.genre || ''] || pi(project)?.genre || '未分类'}
          </span>
        </div>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>受众</span>
          <span className={styles.metaValue}>
            {audienceMap[pi(project)?.targetAudience || ''] || pi(project)?.targetAudience}
          </span>
        </div>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>集数</span>
          <span className={styles.metaValue}>{pi(project)?.totalEpisodes || 0} 集</span>
        </div>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>时长</span>
          <span className={styles.metaValue}>{pi(project)?.episodeDuration || 0}s / 集</span>
        </div>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>章节</span>
          <span className={styles.metaValue}>{pi(project)?.episodesPerChapter || 4} 集/章</span>
        </div>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>模式</span>
          <span className={styles.metaValue}>
            {pi(project)?.productionMode === 'comic_commentary' ? '漫剧解说' : '实时动画'}
          </span>
        </div>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>步骤</span>
          <span className={styles.metaValue}>
            {statusInfo ? `第 ${statusInfo.currentStep} 步` : '-'}
          </span>
        </div>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>创建</span>
          <span className={styles.metaValue}>{formatDate(project.createdAt)}</span>
        </div>
        <div className={styles.metaItem}>
          <span className={styles.metaLabel}>更新</span>
          <span className={styles.metaValue}>{formatDate(project.updatedAt)}</span>
        </div>
      </div>

      {/* 剧本大纲 */}
      {pi(project)?.script?.outline && (
        <div className={styles.outlineSection}>
          <h3 className={styles.sectionLabel}>剧本大纲</h3>
          <div className={styles.outlineContent}>
            <ReactMarkdown>{pi(project)!.script!.outline}</ReactMarkdown>
          </div>
        </div>
      )}

      {/* 当前章节 */}
      {pi(project)?.selectedChapter && (
        <div className={styles.chapterSection}>
          <span className={styles.sectionLabel}>当前章节</span>
          <span className={styles.chapterValue}>{pi(project)!.selectedChapter}</span>
        </div>
      )}
    </div>
  );
}

export default ProjectDetailPage;
