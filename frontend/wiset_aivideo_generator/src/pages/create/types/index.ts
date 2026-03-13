/**
 * 创建流程相关类型定义
 */

/**
 * 步骤配置
 */
export interface Step {
  id: number;
  label: string;
  description: string;
}

/**
 * 创建流程状态
 */
export interface CreateFlowState {
  currentStep: number;
  completedSteps: number[];
}

/**
 * 步骤指示器 Props
 */
export interface StepIndicatorProps {
  steps: Step[];
  currentStep: number;
  completedSteps: number[];
  onStepClick?: (stepId: number) => void;
}

/**
 * 步骤内容组件 Props
 */
export interface StepContentProps {
  onComplete: () => void;
  onBack: () => void;
}
