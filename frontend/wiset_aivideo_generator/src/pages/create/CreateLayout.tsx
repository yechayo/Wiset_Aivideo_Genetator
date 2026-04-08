import { useEffect, useMemo, useCallback, useRef, useState } from 'react';
import { useParams, Navigate, useNavigate } from 'react-router-dom';
import styles from './CreatePage.module.less';
import { CREATE_STEPS } from './constants/steps';
import StepIndicator from './components/StepIndicator';
import { useCreateStore } from '../../stores/createStore';
import { useProjectStore } from '../../stores';
import { useSseProgress } from './steps/hooks/useSseProgress';
import Step1Content from './steps/Step1Content';
import Step2page from './steps/Step2page';
import Step3Merged from './steps/Step3Merged';
import Step4Production from './steps/Step4Production';
import Step5Compose from './steps/Step5Compose';

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
  const { statusInfo, isLoadingStatus, startPolling, stopPolling, syncStatus } = useCreateStore();
  const { currentProject, setCurrentProject } = useProjectStore();
  const [isStepTransitioning, setIsStepTransitioning] = useState(false);

  // Step5 刷新回调
  const [step5RefreshKey, setStep5RefreshKey] = useState(0);

  useSseProgress(currentProject?.projectId, {
    onEpisodeScriptDone: () => {},
    onEpisodePanelDone: () => {},
    onEpisodeGridStatus: () => {},
    onEpisodeComposed: () => {
      setStep5RefreshKey(k => k + 1);
    },
    onStatusChange: (data) => {
      if (currentProject?.projectId && data.to) {
        syncStatus(currentProject.projectId);
      }
    },
    onReconnect: () => {
      if (currentProject?.projectId) {
        syncStatus(currentProject.projectId);
      }
    },
  });

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
  const isCompleted = statusInfo?.statusCode === 'completed';

  useEffect(() => {
    if (!statusInfo || isLoadingStatus) return;

    const backendStep = statusInfo.currentStep;

    // completed 状态允许自由浏览所有步骤，不做路由守卫
    if (isCompleted) return;

    // 跳转到同一个 step 时不重复触发
    if (lastRedirectedStep.current === backendStep) return;

    // draft 状态允许停留在 Step 1 回填/编辑表单，不强制跳转到 Step 2
    if (statusInfo.statusCode === 'draft' && urlStep === 1) return;

    if (urlStep < backendStep) {
      // 后端步骤前进 → 自动跳转
      lastRedirectedStep.current = backendStep;
      navigate(getStepUrl(backendStep), { replace: true });
    } else if (urlStep > backendStep) {
      // 已完成的步骤允许自由浏览，不拉回
      const completedSteps = statusInfo?.completedSteps ?? [];
      if (completedSteps.includes(urlStep)) return;
      // URL 步骤超过后端 → 路由守卫拉回
      lastRedirectedStep.current = backendStep;
      navigate(getStepUrl(backendStep), { replace: true });
    }
  }, [statusInfo, urlStep, isLoadingStatus, navigate, getStepUrl, isCompleted]);

  // 步骤切换时的过渡 loading
  useEffect(() => {
    if (urlStep && currentProject?.projectId) {
      setIsStepTransitioning(true);
      const timer = setTimeout(() => {
        setIsStepTransitioning(false);
      }, 500);
      return () => clearTimeout(timer);
    }
  }, [urlStep, currentProject?.projectId]);

  // 如果没有项目数据且不在第一步，重定向到第一步
  if (!currentProject && urlStep > 1) {
    return <Navigate to={getStepUrl(1)} replace />;
  }

  // 从 statusInfo 取 completedSteps，无 statusInfo 时为空
  // COMPLETED 状态时所有步骤都可自由浏览
  // 非 COMPLETED 时，当前 backendStep 也应可点击（用户需要在 Step 4/5 之间自由切换）
  const effectiveCompletedSteps = useMemo(() => {
    if (isCompleted) return [1, 2, 3, 4, 5];
    const steps = [...(statusInfo?.completedSteps ?? [])];
    const backendStep = statusInfo?.currentStep ?? 1;
    if (!steps.includes(backendStep)) {
      steps.push(backendStep);
    }
    return steps;
  }, [isCompleted, statusInfo]);

  // 已完成步骤 + COMPLETED 状态允许点击步骤导航
  const handleStepClick = (stepId: number) => {
    if (isCompleted || effectiveCompletedSteps.includes(stepId)) {
      navigate(getStepUrl(stepId), { replace: true });
    }
  };

  // 渲染当前步骤内容
  const renderStepContent = () => {
    switch (urlStep) {
      case 1:
        return (
          <Step1Content
            project={currentProject ?? undefined}
            onProjectCreated={(project) => setCurrentProject(project)}
          />
        );
      case 2:
        return currentProject ? (
          <Step2page project={currentProject} onComplete={() => { lastRedirectedStep.current = null; navigate(getStepUrl(3), { replace: true }); }} />
        ) : <Navigate to={getStepUrl(1)} replace />;
      case 3:
        return currentProject ? (
          <Step3Merged project={currentProject} />
        ) : <Navigate to={getStepUrl(1)} replace />;
      case 4:
        return currentProject ? (
          <Step4Production project={currentProject} />
        ) : <Navigate to={getStepUrl(1)} replace />;
      case 5:
        return currentProject ? (
          <Step5Compose key={step5RefreshKey} project={currentProject} />
        ) : <Navigate to={getStepUrl(1)} replace />;
      default:
        return <Navigate to={getStepUrl(1)} replace />;
    }
  };

  const showLoadingOverlay = isStepTransitioning;

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
