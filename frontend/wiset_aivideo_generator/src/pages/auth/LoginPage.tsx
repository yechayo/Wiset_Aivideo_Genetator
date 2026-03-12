import { useState } from 'react';
import styles from './glass-effect.module.less';

function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  // 登录
  const handleLogin = () => {
    if (!username) {
      alert('请输入账号');
      return;
    }
    if (!password) {
      alert('请输入密码');
      return;
    }

    // TODO: 调用登录接口
    console.log('登录:', { username, password });
  };

  // 回车登录
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleLogin();
    }
  };

  return (
    <div className={styles.loginContainer}>
      {/* 背景层 */}
      <div className={styles.loginBackground} />

      {/* 玻璃态登录面板 */}
      <div className={styles.glassPanel}>
        {/* 标题 */}
        <h1 className={styles.loginTitle}>Wiset</h1>
        <p className={styles.loginSubtitle}>开启你的AI视频创作之旅</p>

        {/* 登录表单 */}
        <div className={styles.loginForm}>
          {/* 账号输入 */}
          <div className={styles.inputWrapper}>
            <input
              type="text"
              className={`${styles.glassInput} ${styles.noPrefix}`}
              placeholder="请输入账号"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              onKeyDown={handleKeyDown}
              autoComplete="username"
            />
          </div>

          {/* 密码输入 */}
          <div className={styles.inputWrapper}>
            <input
              type={showPassword ? 'text' : 'password'}
              className={`${styles.glassInput} ${styles.noPrefix}`}
              placeholder="请输入密码"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={handleKeyDown}
              autoComplete="current-password"
            />
            <button
              type="button"
              className={styles.passwordToggle}
              onClick={() => setShowPassword(!showPassword)}
            >
              {showPassword ? '👁️' : '👁️‍🗨️'}
            </button>
          </div>

          {/* 登录按钮 */}
          <button className={styles.loginButton} onClick={handleLogin}>
            立即登录
          </button>
        </div>

        {/* 底部选项 */}
        <div className={styles.loginFooter}>
          <a href="#" className={styles.footerLink}>忘记密码？</a>
          <a href="#" className={styles.footerLink}>注册账号</a>
        </div>

        {/* 协议提示 */}
        <p className={styles.agreementText}>
          登录即代表同意
          <a href="#" className={styles.agreementLink}>用户协议</a>
          {' '}和{' '}
          <a href="#" className={styles.agreementLink}>隐私政策</a>
        </p>
      </div>
    </div>
  );
}

export default LoginPage;
