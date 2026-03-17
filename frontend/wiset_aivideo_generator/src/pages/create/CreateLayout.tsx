import { useEffect, useMemo, useCallback, useRef } from 'react';
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
 *
 * 核心原则：后端状态 = 唯一真理源
 * - 持续轮询后端状态，用 currentStep 做路由守卫
 * - 轮询间隔：生成中 3s，正常 5s
 * - isGenerating 时显示 loading 遮罩，禁止操作
 */
const CreateLayout = () => {
  const { step } = useParams<{ step: string }>();
  const navigate = useNavigate();
  const { statusInfo, isLoadingStatus, startPolling, stopPolling } = useCreateStore();
  const { currentProject, setCurrentProject } = useProjectStore();

  // 动态生成步骤 URL：有 projectId 时用项目路由，否则用 /create
  const getStepUrl = useCallback((stepNum: number) => {
    if (currentProject?.projectId) {
      return `/project/${currentProject.projectId}/step/${stepNum}`;
    }
    return '/create';
  }, [currentProject?.projectId]);

  // 用 ref 记录上次跳转的 step，避免重复跳转
  const lastRedirectedStep = useRef<number | null>(null);

  // 解析 URL 中的步骤
  const urlStep = useMemo(() => {
    if (!step) return 1;
    const stepNum = parseInt(step, 10);
    return Math.min(Math.max(stepNum, 1), CREATE_STEPS.length);
  }, [step]);

  // 组件挂载/卸载：启动/停止轮询
  useEffect(() => {
    if (currentProject?.projectId) {
      startPolling(currentProject.projectId);
    }
    return () => {
      stopPolling();
      lastRedirectedStep.current = null;
    };
  }, [currentProject?.projectId, startPolling, stopPolling]);

  // 路由守卫 + 自动跳转
  useEffect(() => {
    if (!statusInfo || isLoadingStatus) return;

    const backendStep = statusInfo.currentStep;

    // 跳转到同一个 step 时不重复触发
    if (lastRedirectedStep.current === backendStep) return;

    if (urlStep < backendStep) {
      // 后端步骤前进 → 自动跳转
      lastRedirectedStep.current = backendStep;
      navigate(getStepUrl(backendStep), { replace: true });
    } else if (urlStep > backendStep) {
      // URL 步骤超过后端 → 路由守卫拉回
      lastRedirectedStep.current = backendStep;
      navigate(getStepUrl(backendStep), { replace: true });
    }
  }, [statusInfo, urlStep, isLoadingStatus, navigate, getStepUrl]);

  // 步骤导航栏不可点击跳转（确认后不可回退）
  const handleStepClick = undefined;

  // 如果没有项目数据且不在第一步，重定向到第一步
  if (!currentProject && urlStep > 1) {
    return <Navigate to={getStepUrl(1)} replace />;
  }

  // 从 statusInfo 取 completedSteps，无 statusInfo 时为空
  const effectiveCompletedSteps = statusInfo?.completedSteps ?? [];

  // 渲染当前步骤内容
  const renderStepContent = () => {
    switch (urlStep) {
      case 1:
        return (
          <Step1Content
            onComplete={() => {
              // Step1 完成后不需要手动跳转，轮询会自动检测到状态变化
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
              // 确认剧本后轮询会自动检测到状态变化并跳转
            }}
          />
        ) : <Navigate to={getStepUrl(1)} replace />;
      case 3:
        return currentProject ? (
          <Step3page
            project={currentProject}
            onComplete={() => {}}
          />
        ) : <Navigate to={getStepUrl(1)} replace />;
      case 4:
        return currentProject ? (
          <Step4page
            project={currentProject}
            onComplete={() => {}}
          />
        ) : <Navigate to={getStepUrl(1)} replace />;
      case 5:
        return currentProject ? (
          <Step5page
            project={currentProject}
          />
        ) : <Navigate to={getStepUrl(1)} replace />;
      default:
        return <Navigate to={getStepUrl(1)} replace />;
    }
  };

  const showLoadingOverlay = statusInfo?.isGenerating ?? false;

  return (
    <div className={styles.createContainer}>
      {/* Step 指示器 */}
      <StepIndicator
        steps={CREATE_STEPS}
        currentStep={urlStep}
        completedSteps={effectiveCompletedSteps}
        onStepClick={handleStepClick}
      />

      {/* 步骤内容 */}
      {renderStepContent()}

      {/* 全局 loading 遮罩：生成中时禁止操作 */}
      {showLoadingOverlay && (
        <div className={styles.loadingOverlay}>
          <div className={styles.loadingContent}>
            <div className={styles.loadingSpinner} />
            <p>{statusInfo?.statusDescription || '正在生成中...'}</p>
          </div>
        </div>
      )}
    </div>
  );
};

export default CreateLayout;