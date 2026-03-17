import { Navigate } from 'react-router-dom';
import { useCreateStore } from '../../stores/createStore';
import { useProjectStore } from '../../stores';
import type { ReactElement } from 'react';

/**
 * 步骤1包装组件
 */
export const Step1Wrapper = ({ children }: { children: (props: {
  onComplete: () => void;
  onProjectCreated: (project: any) => void;
  onBack: () => void;
}) => ReactElement }) => {
  const { setCurrentProject } = useProjectStore();

  const handleComplete = () => {
    // 步骤完成由轮询自动检测，不需要本地标记
  };

  const handleProjectCreated = (project: any) => {
    setCurrentProject(project);
  };

  return children({
    onComplete: handleComplete,
    onProjectCreated: handleProjectCreated,
    onBack: () => {},
  });
};

/**
 * 步骤2-5包装组件
 */
interface StepWrapperProps {
  children: (props: {
    project: any;
    onComplete: () => void;
    onBack: () => void;
  }) => ReactElement;
  stepNumber: number;
}

export const StepWrapper = ({ children, stepNumber }: StepWrapperProps) => {
  const { currentProject } = useProjectStore();
  const { statusInfo } = useCreateStore();

  // 使用后端状态判断是否可以访问此步骤
  const canAccess = stepNumber === 1
    || (statusInfo?.completedSteps?.includes(stepNumber - 1))
    || stepNumber <= (statusInfo?.currentStep ?? 0);

  if (!canAccess) {
    const targetStep = stepNumber - 1;
    const url = currentProject?.projectId
      ? `/project/${currentProject.projectId}/step/${targetStep}`
      : '/create';
    return <Navigate to={url} replace />;
  }

  if (!currentProject) {
    return <Navigate to="/create" replace />;
  }

  const handleComplete = () => {
    // 步骤完成由轮询自动检测，不需要本地标记
  };

  const handleBack = () => {
    // 导航由组件内部处理
  };

  return children({
    project: currentProject,
    onComplete: handleComplete,
    onBack: handleBack,
  });
};
