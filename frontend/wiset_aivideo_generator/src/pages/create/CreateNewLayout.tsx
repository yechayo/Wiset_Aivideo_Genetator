import { useEffect, useState } from 'react';
import CreateLayout from './CreateLayout';
import { useCreateStore } from '../../stores/createStore';
import { useProjectStore } from '../../stores';

/**
 * 新建项目入口
 *
 * 挂载时清空 store，确保不加载旧项目数据。
 * 在 store 清空完成前不渲染 CreateLayout，防止路由守卫读到旧数据后跳转。
 */
const CreateNewLayout = () => {
  const { clearCurrentProject } = useProjectStore();
  const { resetCreateFlow } = useCreateStore();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    clearCurrentProject();
    resetCreateFlow();
    setReady(true);
  }, [clearCurrentProject, resetCreateFlow]);

  if (!ready) return null;

  return <CreateLayout />;
};

export default CreateNewLayout;
