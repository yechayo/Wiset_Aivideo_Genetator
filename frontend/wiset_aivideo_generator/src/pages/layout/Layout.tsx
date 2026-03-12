import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useEffect } from 'react';
import styles from './Layout.module.less';
import { getUserInfo, clearAuth, isAuthenticated } from '../../utils/tokenStorage';
import { HomeIcon, VideoIcon, FolderIcon, SettingsIcon, LogOutIcon } from '../../components/icons/Icons';

function Layout() {
  const navigate = useNavigate();
  const location = useLocation();
  const userInfo = getUserInfo();

  useEffect(() => {
    // 检查登录状态
    if (!isAuthenticated()) {
      navigate('/login');
    }
  }, [navigate]);

  const handleLogout = () => {
    clearAuth();
    navigate('/login');
  };

  const navItems = [
    { path: '/dashboard', icon: HomeIcon, label: '首页' },
    { path: '/create', icon: VideoIcon, label: '创建视频' },
    { path: '/projects', icon: FolderIcon, label: '我的项目' },
    { path: '/settings', icon: SettingsIcon, label: '设置' },
  ];

  return (
    <div className={styles.layoutContainer}>
      {/* 侧边栏 */}
      <aside className={styles.sidebar}>
        <div className={styles.logo}>
          <div className={styles.logoIcon}>
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 2L2 7L12 12L22 7L12 2Z" fill="currentColor" />
              <path d="M2 17L12 22L22 17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M2 12L12 17L22 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <h1>Wiset</h1>
          <p>AI视频创作平台</p>
        </div>

        <nav className={styles.nav}>
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = location.pathname === item.path;
            return (
              <Link
                key={item.path}
                to={item.path}
                className={`${styles.navItem} ${isActive ? styles.active : ''}`}
              >
                <Icon className={styles.navIcon} />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </nav>

        {/* 用户信息 */}
        <div className={styles.userSection}>
          <div className={styles.userInfo}>
            <div className={styles.avatar}>
              {userInfo?.username?.charAt(0).toUpperCase() || 'U'}
            </div>
            <div className={styles.userDetails}>
              <span className={styles.username}>{userInfo?.username || '用户'}</span>
              <span className={styles.userId}>ID: {userInfo?.userId || '-'}</span>
            </div>
          </div>
          <button className={styles.logoutBtn} onClick={handleLogout}>
            <LogOutIcon className={styles.logoutIcon} />
            <span>退出登录</span>
          </button>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className={styles.mainContent}>
        <Outlet />
      </main>
    </div>
  );
}

export default Layout;
