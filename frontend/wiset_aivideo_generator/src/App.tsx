import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from './pages/auth/LoginPage';
import Layout from './pages/layout/Layout';
import Dashboard from './pages/dashboard/Dashboard';
import ProjectsPage from './pages/projects/ProjectsPage';
import ProjectDetailPage from './pages/projects/ProjectDetailPage';
import { useAuthStore } from './stores/authStore';
import { ToastProvider, ToastContainer } from './components/toast';
import CreateNewLayout from './pages/create/CreateNewLayout';
import ProjectStepLayout from './pages/create/ProjectStepLayout';
import PanelProductionPage from './pages/create/steps/PanelProductionPage';

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
            <Route path="projects" element={<ProjectsPage />} />
            <Route path="projects/:projectId" element={<ProjectDetailPage />} />
            <Route path="settings" element={<Dashboard />} />

            {/* 创建流程路由 */}
            <Route path="create" element={<CreateNewLayout />} />
            <Route path="project/:projectId/step/:step?" element={<ProjectStepLayout />} />
            <Route path="project/:projectId/episode/:episodeId/panel/:panelIndex" element={<PanelProductionPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
      <ToastContainer />
    </ToastProvider>
  );
}

export default App;
