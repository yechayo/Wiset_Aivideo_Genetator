import { Link } from 'react-router-dom';
import styles from './Dashboard.module.less';
import { FilmIcon, FolderIcon, BookOpenIcon, PlusIcon } from '../../components/icons/Icons';

function Dashboard() {
  const stats = [
    { value: '0', label: '创建视频数', trend: '+0 本周' },
    { value: '0', label: '总时长（分钟）', trend: '+0 本周' },
    { value: '0', label: '本月生成', trend: '- vs 上月' },
    { value: '0', label: '存储使用', trend: '0 MB / 10 GB' },
  ];

  const quickActions = [
    {
      icon: FilmIcon,
      title: '创建新视频',
      description: '使用 AI 生成专业视频内容',
      to: '/create',
    },
    {
      icon: FolderIcon,
      title: '我的项目',
      description: '管理和编辑已有项目',
      to: '/projects',
    },
    {
      icon: BookOpenIcon,
      title: '使用教程',
      description: '学习平台功能和使用技巧',
      to: '/tutorials',
    },
  ];

  return (
    <div className={styles.dashboardContainer}>
      {/* 欢迎头部 */}
      <div className={styles.header}>
        <div className={styles.headerContent}>
          <h1>欢迎回来 👋</h1>
          <p className={styles.subtitle}>开始你的 AI 视频创作之旅</p>
        </div>
        <Link to="/create" className={styles.createButton}>
          <PlusIcon className={styles.plusIcon} />
          <span>新建项目</span>
        </Link>
      </div>

      {/* 数据统计卡片 */}
      <div className={styles.statsSection}>
        {stats.map((stat, index) => (
          <div key={index} className={styles.statCard}>
            <div className={styles.statHeader}>
              <span className={styles.statValue}>{stat.value}</span>
              <div className={styles.statIcon}>
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M13 2L3 14h8l-1 8 10-12h-8l1-8z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
              </div>
            </div>
            <div className={styles.statInfo}>
              <span className={styles.statLabel}>{stat.label}</span>
              <span className={styles.statTrend}>{stat.trend}</span>
            </div>
          </div>
        ))}
      </div>

      {/* 快速操作 */}
      <div className={styles.actionsSection}>
        <h2 className={styles.sectionTitle}>快速开始</h2>
        <div className={styles.quickActions}>
          {quickActions.map((action) => {
            const Icon = action.icon;
            return (
              <Link key={action.to} to={action.to} className={styles.actionCard}>
                <div className={styles.actionIcon}>
                  <Icon />
                </div>
                <div className={styles.actionContent}>
                  <h3 className={styles.actionTitle}>{action.title}</h3>
                  <p className={styles.actionDescription}>{action.description}</p>
                </div>
                <div className={styles.actionArrow}>
                  <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M5 12h14M12 5l7 7-7 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                </div>
              </Link>
            );
          })}
        </div>
      </div>

      {/* 最近项目 */}
      <div className={styles.projectsSection}>
        <div className={styles.projectsHeader}>
          <h2 className={styles.sectionTitle}>最近项目</h2>
          <Link to="/projects" className={styles.viewAllLink}>
            查看全部
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M5 12h14M12 5l7 7-7 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </Link>
        </div>

        {/* 空状态 */}
        <div className={styles.emptyState}>
          <div className={styles.emptyIcon}>
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </div>
          <h3 className={styles.emptyTitle}>还没有项目</h3>
          <p className={styles.emptyDescription}>创建你的第一个 AI 视频项目</p>
          <Link to="/create" className={styles.emptyButton}>
            <PlusIcon />
            <span>创建视频</span>
          </Link>
        </div>
      </div>
    </div>
  );
}

export default Dashboard;
