import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from './pages/auth/LoginPage';
import Layout from './pages/layout/Layout';
import Dashboard from './pages/dashboard/Dashboard';
import { useAuthStore } from './stores/authStore';
import { ToastProvider, ToastContainer } from './components/toast';
import CreateLayout from './pages/create/CreateLayout';

// 受保护的路由组件
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return isAuthenticated() ? <>{children}</> : <Navigate to="/login" replace />;
}

function App() {
  return (
    <ToastProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <Layout />
              </ProtectedRoute>
            }
          >
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="projects" element={<Dashboard />} />
            <Route path="settings" element={<Dashboard />} />

            {/* 创建流程路由 - 所有步骤都由 CreateLayout 处理 */}
            <Route path="create" element={<CreateLayout />} />
            <Route path="create/:step" element={<CreateLayout />} />
          </Route>
        </Routes>
      </BrowserRouter>
      <ToastContainer />
    </ToastProvider>
  );
}

export default App;
