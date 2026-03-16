import { useEffect, useMemo } from 'react';
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
 */
const CreateLayout = () => {
  const { step } = useParams<{ step: string }>();
  const navigate = useNavigate();
  const { completedSteps, setCurrentStep, addCompletedStep } = useCreateStore();
  const { currentProject, setCurrentProject } = useProjectStore();

  // 解析当前步骤
  const currentStep = useMemo(() => {
    if (!step) return 1;
    const stepNum = parseInt(step, 10);
    return Math.min(Math.max(stepNum, 1), CREATE_STEPS.length);
  }, [step]);

  // 只在步骤变化时更新 store
  useEffect(() => {
    setCurrentStep(currentStep);
  }, [currentStep, setCurrentStep]);

  // 处理步骤点击
  const handleStepClick = (stepId: number) => {
    if (completedSteps.includes(stepId)) {
      navigate(`/create/${stepId}`);
    }
  };

  // 如果没有项目数据且不在第一步，重定向到第一步
  if (!currentProject && currentStep > 1) {
    return <Navigate to="/create/1" replace />;
  }

  // 渲染当前步骤内容
  const renderStepContent = () => {
    switch (currentStep) {
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
        currentStep={currentStep}
        completedSteps={completedSteps}
        onStepClick={handleStepClick}
      />

      {/* 步骤内容 */}
      {renderStepContent()}
    </div>
  );
};

export default CreateLayout;
