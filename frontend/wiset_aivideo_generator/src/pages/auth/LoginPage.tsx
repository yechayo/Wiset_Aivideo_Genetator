import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import styles from './glass-effect.module.less';
import { register, login } from '../../services/authService';
import { setAuthData } from '../../utils/tokenStorage';
import { EyeIcon, EyeOffIcon } from '../../components/icons/Icons';

type AuthMode = 'login' | 'register';

function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<AuthMode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [email, setEmail] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);

  // 登录
  const handleLogin = async () => {
    if (!username) {
      alert('请输入账号');
      return;
    }
    if (username.length < 3 || username.length > 20) {
      alert('账号长度应为3-20个字符');
      return;
    }
    if (!password) {
      alert('请输入密码');
      return;
    }
    if (password.length < 6 || password.length > 50) {
      alert('密码长度应为6-50个字符');
      return;
    }

    setIsLoading(true);
    try {
      const response = await login({ username, password });
      if (response.data) {
        const { accessToken, refreshToken, username: returnedUsername, userId } = response.data;
        setAuthData(accessToken, refreshToken, { username: returnedUsername, userId });
        alert('登录成功！');
        navigate('/dashboard');
      }
    } catch (error) {
      alert(error instanceof Error ? error.message : '登录失败，请稍后重试');
    } finally {
      setIsLoading(false);
    }
  };

  // 注册
  const handleRegister = async () => {
    if (!username) {
      alert('请输入账号');
      return;
    }
    if (username.length < 3 || username.length > 20) {
      alert('账号长度应为3-20个字符');
      return;
    }
    if (!password) {
      alert('请输入密码');
      return;
    }
    if (password.length < 6 || password.length > 50) {
      alert('密码长度应为6-50个字符');
      return;
    }
    if (!confirmPassword) {
      alert('请确认密码');
      return;
    }
    if (password !== confirmPassword) {
      alert('两次输入的密码不一致');
      return;
    }
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      alert('请输入有效的邮箱地址');
      return;
    }

    setIsLoading(true);
    try {
      const response = await register({
        username,
        password,
        email: email || undefined,
      });
      if (response.data) {
        const { accessToken, refreshToken, username: returnedUsername, userId } = response.data;
        setAuthData(accessToken, refreshToken, { username: returnedUsername, userId });
        alert('注册成功！');
        navigate('/dashboard');
      }
    } catch (error) {
      alert(error instanceof Error ? error.message : '注册失败，请稍后重试');
    } finally {
      setIsLoading(false);
    }
  };

  // 切换登录/注册模式
  const toggleMode = () => {
    setMode(mode === 'login' ? 'register' : 'login');
    // 清空表单
    setUsername('');
    setPassword('');
    setConfirmPassword('');
    setEmail('');
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
        <p className={styles.loginSubtitle}>
          {mode === 'login' ? '开启你的AI视频创作之旅' : '创建你的账号'}
        </p>

        {/* 登录/注册表单 */}
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
              className={`${styles.glassInput} ${styles.noPrefix} ${styles.withToggle}`}
              placeholder="请输入密码"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={handleKeyDown}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            />
            <button
              type="button"
              className={styles.passwordToggle}
              onClick={() => setShowPassword(!showPassword)}
              aria-label={showPassword ? '隐藏密码' : '显示密码'}
            >
              {showPassword ? <EyeOffIcon /> : <EyeIcon />}
            </button>
          </div>

          {/* 邮箱输入 - 仅注册模式显示 */}
          {mode === 'register' && (
            <div className={styles.inputWrapper}>
              <input
                type="email"
                className={`${styles.glassInput} ${styles.noPrefix}`}
                placeholder="邮箱（可选）"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                onKeyDown={handleKeyDown}
                autoComplete="email"
              />
            </div>
          )}

          {/* 确认密码输入 - 仅注册模式显示 */}
          {mode === 'register' && (
            <div className={styles.inputWrapper}>
              <input
                type={showConfirmPassword ? 'text' : 'password'}
                className={`${styles.glassInput} ${styles.noPrefix} ${styles.withToggle}`}
                placeholder="请确认密码"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                onKeyDown={handleKeyDown}
                autoComplete="new-password"
              />
              <button
                type="button"
                className={styles.passwordToggle}
                onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                aria-label={showConfirmPassword ? '隐藏密码' : '显示密码'}
              >
                {showConfirmPassword ? <EyeOffIcon /> : <EyeIcon />}
              </button>
            </div>
          )}

          {/* 登录/注册按钮 */}
          <button
            className={styles.loginButton}
            onClick={mode === 'login' ? handleLogin : handleRegister}
            disabled={isLoading}
          >
            {isLoading ? '处理中...' : (mode === 'login' ? '立即登录' : '立即注册')}
          </button>
        </div>

        {/* 底部选项 */}
        <div className={styles.loginFooter}>
          <a href="#" className={styles.footerLink}>忘记密码？</a>
          <button
            type="button"
            className={styles.modeToggleBtn}
            onClick={toggleMode}
          >
            {mode === 'login' ? '注册账号' : '返回登录'}
          </button>
        </div>

        {/* 协议提示 */}
        <p className={styles.agreementText}>
          {mode === 'login' ? '登录' : '注册'}即代表同意
          <a href="#" className={styles.agreementLink}>用户协议</a>
          {' '}和{' '}
          <a href="#" className={styles.agreementLink}>隐私政策</a>
        </p>
      </div>
    </div>
  );
}

export default LoginPage;
