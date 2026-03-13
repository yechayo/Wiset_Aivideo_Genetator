import { useState } from 'react';
import styles from './CreatePage.module.less';
import type { Project } from '../../services';
import { CREATE_STEPS } from './constants/steps';
import StepIndicator from './components/StepIndicator';
import Step1Content from './steps/Step1Content';
import Step2page from './steps/Step2page';
import Step3page from './steps/Step3page';
import Step4page from './steps/Step4page';
import Step5page from './steps/Step5page';

/**
 * 创建流程页面容器
 * 统一管理步骤状态，渲染对应的步骤内容
 */
const CreatePage = () => {
  // 步骤状态
  const [currentStep, setCurrentStep] = useState(1);
  const [completedSteps, setCompletedSteps] = useState<number[]>([]);
  const [projectData, setProjectData] = useState<Project | null>(null);

  // 处理步骤完成
  const handleStepComplete = () => {
    setCompletedSteps((prev) => [...prev, currentStep]);
    setCurrentStep((prev) => Math.min(prev + 1, CREATE_STEPS.length));
  };

  // 处理步骤跳转（仅可跳转到已完成的步骤）
  const handleStepClick = (stepId: number) => {
    if (completedSteps.includes(stepId)) {
      setCurrentStep(stepId);
    }
  };

  // 处理项目创建完成
  const handleProjectCreated = (project: Project) => {
    setProjectData(project);
  };

  // 渲染当前步骤内容
  const renderStepContent = () => {
    // 如果没有项目数据且不在第一步，显示第一步
    if (!projectData && currentStep > 1) {
      setCurrentStep(1);
      return null;
    }

    switch (currentStep) {
      case 1:
        return (
          <Step1Content
            onComplete={handleStepComplete}
            onProjectCreated={handleProjectCreated}
            onBack={() => {}}
          />
        );
      case 2:
        return projectData ? (
          <Step2page
            project={projectData}
            onComplete={handleStepComplete}
            onBack={() => handleStepClick(1)}
          />
        ) : null;
      case 3:
        return projectData ? (
          <Step3page
            project={projectData}
            onComplete={handleStepComplete}
            onBack={() => handleStepClick(2)}
          />
        ) : null;
      case 4:
        return projectData ? (
          <Step4page
            project={projectData}
            onComplete={handleStepComplete}
            onBack={() => handleStepClick(3)}
          />
        ) : null;
      case 5:
        return projectData ? (
          <Step5page
            project={projectData}
            onComplete={() => {}}
            onBack={() => handleStepClick(4)}
          />
        ) : null;
      default:
        return null;
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

export default CreatePage;
