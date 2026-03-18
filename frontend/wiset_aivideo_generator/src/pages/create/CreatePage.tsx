import { useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useCreateStore } from '../../stores/createStore';
import { useProjectStore } from '../../stores';

/**
 * 创建流程入口页面
 * 重定向到当前步骤
 */
const CreatePage = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { statusInfo } = useCreateStore();
  const { currentProject } = useProjectStore();
  const [hasRedirected, setHasRedirected] = useState(false);

  useEffect(() => {
    // 防止多次重定向
    if (hasRedirected) return;

    // 如果已经在 /create/:step 路径上，不需要重定向
    if (location.pathname.match(/^\/create\/\d+$/)) {
      return;
    }

    // 计算目标步骤
    let targetStep: number;
    if (currentProject && statusInfo?.currentStep) {
      // 如果有项目，从 store 中获取当前步骤
      targetStep = statusInfo.currentStep;
    } else {
      // 没有项目，从第一步开始
      targetStep = 1;
    }

    setHasRedirected(true);
    navigate(`/create/${targetStep}`, { replace: true });
  }, [hasRedirected, currentProject, statusInfo?.currentStep, location.pathname, navigate]);

  // 渲染空白（会立即重定向）
  return null;
};

export default CreatePage;
