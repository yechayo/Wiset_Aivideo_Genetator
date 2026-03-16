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
  const { addCompletedStep } = useCreateStore();
  const { setCurrentProject } = useProjectStore();

  const handleComplete = () => {
    addCompletedStep(1);
    // 导航由组件内部处理
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
  const { completedSteps, addCompletedStep } = useCreateStore();

  // 检查是否可以访问此步骤
  const canAccess = stepNumber === 1 || completedSteps.includes(stepNumber - 1);

  if (!canAccess) {
    return <Navigate to={`/create/${stepNumber - 1}`} replace />;
  }

  if (!currentProject) {
    return <Navigate to="/create/1" replace />;
  }

  const handleComplete = () => {
    addCompletedStep(stepNumber);
    // 导航由组件内部处理
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
