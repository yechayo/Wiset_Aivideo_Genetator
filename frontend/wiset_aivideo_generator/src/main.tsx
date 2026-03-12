import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

// 暂时移除 StrictMode 调试密码切换按钮重复显示问题
createRoot(document.getElementById('root')!).render(
  <App />
)
