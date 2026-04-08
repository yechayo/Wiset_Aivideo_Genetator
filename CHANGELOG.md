# Changelog

## [2026-04-08]

- feat: 集成 Grok 视频生成服务与 Panel 生产流程优化
- feat: 视频合并时 TTS 与视频原声同等音量
- docs: 更新逐集流水线设计文档
- feat: 项目列表页添加删除按钮
- feat: EpisodeState 添加逐集合成视频字段
- feat: 流水线新增 Stage 2 逐 Panel 旁白精修

## [2026-04-07]

- feat: 漫剧提示词优化、视频时长限制及开发环境配置更新

## [2026-04-03]

- feat: 增强九宫格提示词角色锚定 + Step4全屏布局与Lightbox
- feat: 增强分镜提示词与跨面板衔接 + 前端步骤映射修正

## [2026-04-01]

- fix: 第五步管线全面修复与优化
- fix: SSE callbacks create placeholder episodes when chapters is empty
- fix: add initial SSE connected event and debug logging
- fix: SSE connection URL and auth - use API_BASE_URL and token query param
- feat: integrate SSE progress, remove blocking spinner, add generation progress bar
- feat: add script/storyboard/grid generating states to EpisodeCard
- feat: add useSseProgress hook for SSE real-time progress subscription
- feat: add skeleton and shimmer animation styles for episode loading states
- feat: add scriptStatus and storyboardStatus to EpisodeState
- feat: broadcast episode grid status events in GridImageService
- feat: broadcast episode script and storyboard progress events in StoryboardService
- feat: add broadcastEpisodeProgress method to ProjectStatusBroadcaster
- fix: 修复轮询间隔常量和 AbortController 组件卸载清理
- fix: 添加 @Slf4j 注解修复编译错误
- fix: 前端轮询添加最大重试次数和 AbortController，删除重复接口声明
- fix: 移除 rejected 状态审核绕过，改善融合图失败日志
- fix: 九宫格异步生成添加版本检查，防止旧线程覆盖新状态
- fix: FFmpeg concat 改用 libx264+aac 重新编码，修复不同编码视频拼接失败
- fix: 融合图固定输出 1920x1080 (16:9)，确保 AI 视频生成尺寸一致
- fix: 统一融合图↔视频prompt编号体系，修复第2个Panel开始映射断裂
- fix: 前端 canvasSplitter 同步分隔线补偿逻辑
- fix: 九宫格分割算法添加分隔线补偿，修复像素累积偏差
- fix: 修复大纲生成与章节提取的数据链路问题
- feat: 分镜中注入角色ID，GridImageService优先用charId匹配角色参考图
- fix: 限制分集剧本生成为用户指定的集数
- feat: 实现 getCharacterDescriptions 从数据库获取角色设定
- fix: 修复 StoryboardService 读取大纲路径错误

## [2026-03-25]

- feat: 分镜生产页重构为步骤导航模式并优化交互体验

## [2026-03-23]

- docs: update changelog with 2026-03-23 entries
- feat: 全栈增强——后端服务重构与前端UI样式系统重写
- docs(plan): mark P6/P7 completed and record final checkpoint
- test(controller): expand submit-fusion-page boundary coverage
- test(production): add split-to-fusion chain regression
- test(production): harden mixed-layout fusion pagination regressions
- feat(production): integrate backend grid-page split flow and controller tests
- feat(production): implement P5 storyboard enhancement service
- feat(production): implement P4 backend grid split service
- chore: 提交其余工作区改动
- feat(storyboard): 支持场景组分页网格与融合映射

## [2026-03-20]

- feat: 重构Grid融合为逐格模式，支持每格独立融合参考图
