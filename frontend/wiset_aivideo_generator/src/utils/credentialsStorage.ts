/**
 * 凭证存储管理工具
 * 用于保存和获取用户登录的账号密码
 */

const CREDENTIALS_KEY = 'wiset_saved_credentials';

/**
 * 保存的凭证数据结构
 */
export interface SavedCredentials {
  username: string;
  password: string; // Base64编码
  savedAt: number;
}

/**
 * 将字符串编码为Base64
 */
function encodeBase64(str: string): string {
  try {
    return btoa(unescape(encodeURIComponent(str)));
  } catch {
    return str; // 编码失败时返回原字符串
  }
}

/**
 * 将Base64解码为字符串
 */
function decodeBase64(encoded: string): string {
  try {
    return decodeURIComponent(escape(atob(encoded)));
  } catch {
    return encoded; // 解码失败时返回原字符串
  }
}

/**
 * 保存登录凭证
 * @param username 用户名
 * @param password 密码（明文，函数内部会编码）
 */
export function saveCredentials(username: string, password: string): void {
  const credentials: SavedCredentials = {
    username,
    password: encodeBase64(password),
    savedAt: Date.now(),
  };
  localStorage.setItem(CREDENTIALS_KEY, JSON.stringify(credentials));
}

/**
 * 获取保存的凭证
 * @returns 保存的凭证，如果不存在则返回null
 */
export function getCredentials(): SavedCredentials | null {
  const data = localStorage.getItem(CREDENTIALS_KEY);
  if (!data) return null;

  try {
    const credentials: SavedCredentials = JSON.parse(data);
    // 解码密码
    return {
      ...credentials,
      password: decodeBase64(credentials.password),
    };
  } catch {
    // 数据损坏，清除并返回null
    clearCredentials();
    return null;
  }
}

/**
 * 清除保存的凭证
 */
export function clearCredentials(): void {
  localStorage.removeItem(CREDENTIALS_KEY);
}

/**
 * 检查是否有保存的凭证
 * @returns 是否存在保存的凭证
 */
export function hasCredentials(): boolean {
  return !!localStorage.getItem(CREDENTIALS_KEY);
}
