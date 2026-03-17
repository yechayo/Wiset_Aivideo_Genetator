import { useEffect, useMemo, useCallback } from 'react';
import { useParams, Navigate, useNavigate } from 'react-router-dom';
import styles from './CreatePage.module.less';
import { CREATE_STEPS } from './constants/steps';
import StepIndicator from './components/StepIndicator';
import { useCreateStore } from '../../stores/createStore';
import { useProjectStore } from '../../stores';
import Step1Content from './steps/Step1Content';
import Step2page from './steps/Step2page';
import Step3page from './steps/Step3page';
import Step4page from './steps/Step4page';
import Step5page from './steps/Step5page';

/**
 * 创建流程布局组件
 * 渲染步骤指示器和当前步骤内容
 * 自动从后端同步项目状态，确保前后端步骤一致
 */
const CreateLayout = () => {
  const { step } = useParams<{ step: string }>();
  const navigate = useNavigate();
  const { completedSteps, currentStep, statusInfo, isLoadingStatus, setCurrentStep, addCompletedStep, syncStatus } = useCreateStore();
  const { currentProject, setCurrentProject } = useProjectStore();

  // 解析 URL 中的步骤
  const urlStep = useMemo(() => {
    if (!step) return 1;
    const stepNum = parseInt(step, 10);
    return Math.min(Math.max(stepNum, 1), CREATE_STEPS.length);
  }, [step]);

  // 项目变化时自动从后端同步状态
  useEffect(() => {
    if (currentProject?.projectId) {
      syncStatus(currentProject.projectId);
    }
  }, [currentProject?.projectId, syncStatus]);

  // 根据后端状态验证/重定向步骤
  useEffect(() => {
    if (!statusInfo || !currentProject) return;

    // 如果 URL 步骤大于后端当前步骤，重定向到后端当前步骤
    if (urlStep > statusInfo.currentStep) {
      navigate(`/create/${statusInfo.currentStep}`, { replace: true });
    }
  }, [statusInfo, urlStep, currentProject, navigate]);

  // 同步 store 中的当前步骤（只在合法范围内）
  useEffect(() => {
    if (statusInfo) {
      setCurrentStep(Math.min(urlStep, statusInfo.currentStep));
    } else {
      setCurrentStep(urlStep);
    }
  }, [urlStep, statusInfo, setCurrentStep]);

  // 处理步骤点击：只允许跳转到已完成的步骤或当前步骤
  const handleStepClick = useCallback((stepId: number) => {
    if (statusInfo) {
      // 使用后端返回的已完成步骤来判断
      if (statusInfo.completedSteps.includes(stepId) || stepId === statusInfo.currentStep) {
        navigate(`/create/${stepId}`);
      }
    } else {
      // 回退到本地判断
      if (completedSteps.includes(stepId)) {
        navigate(`/create/${stepId}`);
      }
    }
  }, [statusInfo, completedSteps, navigate]);

  // 如果没有项目数据且不在第一步，重定向到第一步
  if (!currentProject && urlStep > 1) {
    return <Navigate to="/create/1" replace />;
  }

  // 使用后端状态信息中的完成步骤（如果有），否则用本地状态
  const effectiveCompletedSteps = statusInfo ? statusInfo.completedSteps : completedSteps;
  const effectiveCurrentStep = statusInfo ? Math.min(urlStep, statusInfo.currentStep) : urlStep;

  // 渲染当前步骤内容
  const renderStepContent = () => {
    switch (effectiveCurrentStep) {
      case 1:
        return (
          <Step1Content
            onComplete={() => {
              addCompletedStep(1);
              navigate('/create/2');
            }}
            onProjectCreated={(project) => setCurrentProject(project)}
            onBack={() => {}}
          />
        );
      case 2:
        return currentProject ? (
          <Step2page
            project={currentProject}
            onComplete={() => {
              addCompletedStep(2);
              navigate('/create/3');
            }}
            onBack={() => {
              navigate('/create/1');
            }}
          />
        ) : <Navigate to="/create/1" replace />;
      case 3:
        return currentProject ? (
          <Step3page
            project={currentProject}
            onComplete={() => {
              addCompletedStep(3);
              navigate('/create/4');
            }}
            onBack={() => {
              navigate('/create/2');
            }}
          />
        ) : <Navigate to="/create/1" replace />;
      case 4:
        return currentProject ? (
          <Step4page
            project={currentProject}
            onComplete={() => {
              addCompletedStep(4);
              navigate('/create/5');
            }}
            onBack={() => {
              navigate('/create/3');
            }}
          />
        ) : <Navigate to="/create/1" replace />;
      case 5:
        return currentProject ? (
          <Step5page
            project={currentProject}
            onComplete={() => {}}
            onBack={() => {
              navigate('/create/4');
            }}
          />
        ) : <Navigate to="/create/1" replace />;
      default:
        return <Navigate to="/create/1" replace />;
    }
  };

  return (
    <div className={styles.createContainer}>
      {/* Step 指示器 */}
      <StepIndicator
        steps={CREATE_STEPS}
        currentStep={effectiveCurrentStep}
        completedSteps={effectiveCompletedSteps}
        onStepClick={handleStepClick}
      />

      {/* 步骤内容 */}
      {renderStepContent()}
    </div>
  );
};

export default CreateLayout;
