# Vite + React + Tauri 打包 exe 完整指南

> 基于 [Tauri 官方文档 v2](https://v2.tauri.app/) 和 [Vite 官方配置](https://v2.tauri.app/start/frontend/vite/)

---

## 一、创建项目

### 方式1：使用 create-tauri-app（推荐）

```powershell
# 使用官方脚手架
npm create tauri-app@latest

# 或使用 pnpm
pnpm create tauri-app

# 选择：
# - Project name: my-app
# - Frontend: React (TypeScript)
# - Package manager: pnpm/npm/yarn
```

### 方式2：在现有 Vite 项目中添加 Tauri

```powershell
# 进入现有项目目录
cd frontend/wiset_aivideo_generator

# 安装 Tauri CLI（推荐作为开发依赖）
pnpm add -D @tauri-apps/cli@latest

# 初始化 Tauri
pnpm tauri init
```

**`tauri init` 交互式提示：**
- App name: `Wiset`
- Window title: `Wiset`
- Dist directory: `../dist`（默认）
- Dev URL: `http://localhost:5173`（默认）

---

## 二、配置文件

### 1. `src-tauri/tauri.conf.json`（Tauri 2.x 官方配置）

```json
{
  "$schema": "https://schema.tauri.app/config/2",
  "productName": "Wiset",
  "version": "0.1.0",
  "identifier": "com.wiset.app",
  "build": {
    "beforeDevCommand": "pnpm dev",
    "beforeBuildCommand": "pnpm build",
    "devUrl": "http://localhost:5173",
    "frontendDist": "../dist"
  },
  "app": {
    "windows": [
      {
        "title": "Wiset",
        "width": 1200,
        "height": 800,
        "resizable": true,
        "fullscreen": false
      }
    ],
    "security": {
      "csp": null
    }
  },
  "bundle": {
    "active": true,
    "targets": "all",
    "icon": [
      "icons/32x32.png",
      "icons/128x128.png",
      "icons/128x128@2x.png",
      "icons/icon.icns",
      "icons/icon.ico"
    ]
  }
}
```

### 2. `vite.config.ts`（官方推荐配置）

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const host = process.env.TAURI_DEV_HOST;

export default defineConfig({
  plugins: [react()],

  // 防止 vite 遮蔽 rust 错误
  clearScreen: false,

  server: {
    // 确保端口与 tauri.conf.json 中的 devUrl 匹配
    port: 5173,
    // Tauri 需要固定端口，如果端口被占用则失败
    strictPort: true,
    // 如果设置了 Tauri 期望的主机，使用它
    host: host || false,
    hmr: host ? {
      protocol: 'ws',
      host,
      port: 1421,
    } : undefined,
    watch: {
      // 告诉 vite 忽略监听 `src-tauri`
      ignored: ['**/src-tauri/**'],
    },
  },

  // 环境变量前缀
  envPrefix: ['VITE_', 'TAURI_ENV_*'],

  build: {
    // Tauri 在 Windows 上使用 Chromium，在 macOS 和 Linux 上使用 WebKit
    target:
      process.env.TAURI_ENV_PLATFORM == 'windows'
        ? 'chrome105'
        : 'safari13',
    // 调试构建时不压缩
    minify: !process.env.TAURI_ENV_DEBUG ? 'esbuild' : false,
    // 调试构建时生成 sourcemap
    sourcemap: !!process.env.TAURI_ENV_DEBUG,
  },
});
```

### 3. `package.json` 脚本（官方示例）

```json
{
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview",
    "tauri": "tauri"
  }
}
```

或更详细的版本：

```json
{
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview",
    "tauri:dev": "tauri dev",
    "tauri:build": "tauri build"
  }
}
```

---

## 三、开发与打包

### 开发模式

```powershell
# 启动 Tauri 开发模式
npm run tauri:dev

# 这会自动：
# 1. 运行 beforeDevCommand（npm run dev，启动 Vite 开发服务器）
# 2. 打开 Tauri 窗口加载前端页面
# 3. 支持热重载
```

### 打包成 exe

```powershell
# 打包发布版本
npm run tauri:build

# 首次打包会下载并编译 Rust 依赖，可能需要较长时间
# 后续打包会使用缓存，速度会快很多

# 生成的文件位置：
# - Windows 安装包: src-tauri/target/release/bundle/nsis/Wiset_0.1.0_x64-setup.exe
# - 单文件 exe:     src-tauri/target/release/Wiset.exe
```

---

## 四、项目结构

```
frontend/wiset_aivideo_generator/
├── src/                    # React 源码
│   ├── main.tsx
│   ├── App.tsx
│   └── ...
├── public/                 # 静态资源
├── src-tauri/              # Tauri Rust 后端
│   ├── src/
│   │   └── main.rs         # Rust 入口
│   ├── icons/              # 应用图标
│   ├── tauri.conf.json     # Tauri 配置
│   ├── Cargo.toml          # Rust 依赖
│   └── capabilities/       # Tauri 2.x 权限配置
├── dist/                   # Vite 构建输出（自动生成）
├── node_modules/
├── vite.config.ts
├── package.json
├── tsconfig.json
└── index.html
```

---

## 五、常用命令速查

| 命令 | 作用 |
|------|------|
| `npm create tauri-app` | 创建新项目 |
| `npx tauri init` | 在现有项目中初始化 Tauri |
| `npm run tauri:dev` | 开发模式 |
| `npm run tauri:build` | 打包成 exe |
| `npx tauri icon <png>` | 从 PNG 生成图标 |

---

## 六、检查清单（官方）

- [ ] 使用 `../dist` 作为 `frontendDist`
- [ ] Vite 开发服务器端口配置为 `5173` 并启用 `strictPort`
- [ ] 使用 `process.env.TAURI_DEV_HOST` 作为开发服务器主机 IP（用于 iOS 真机）
- [ ] 配置 `envPrefix: ['VITE_', 'TAURI_ENV_*']`
- [ ] Vite 配置 `clearScreen: false`

---

## 七、注意事项

1. **首次打包较慢**：Rust 编译需要时间（5-15分钟），后续打包会快很多
2. **端口占用**：Vite 默认使用 5173 端口，确保该端口未被占用
3. **WebView2**：Windows 用户必须安装 WebView2（Windows 10 1809+ 自带）
4. **杀毒软件**：打包后的 exe 可能被误报，需要添加信任
5. **Tauri 2.x**：使用 `capabilities` 目录配置权限，而不是 Tauri 1.x 的 `allowlist`

---

## 八、参考文档

- [Tauri 官方文档](https://v2.tauri.app/)
- [Tauri + Vite 配置](https://v2.tauri.app/start/frontend/vite/)
- [Tauri 前端配置概览](https://v2.tauri.app/start/frontend/)
- [Tauri API 参考](https://v2.tauri.app/reference/js/)
- [Vite 官方文档](https://vite.dev/config/)
