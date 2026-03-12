# Tauri 环境安装指南

> 本指南帮助你在 Windows 环境下配置 Tauri 开发环境，将 Vite + React 项目打包成 exe 桌面应用。

## 环境检查结果

| 依赖 | 状态 | 版本 |
|------|------|------|
| Node.js | ✅ 已安装 | v20.19.5 |
| npm | ✅ 已安装 | 10.8.2 |
| WebView2 | ✅ 已安装 | 145.0.3800.97 |
| **Rust** | ❌ 需安装 | - |
| **MSVC Build Tools** | ❌ 需安装 | - |

---

## 步骤 1：安装 Microsoft C++ Build Tools

### 下载安装

1. 访问：https://visualstudio.microsoft.com/visual-cpp-build-tools/
2. 点击 **"下载 Build Tools"**
3. 运行下载的 `vs_BuildTools.exe`

### 安装配置

1. 在安装界面中，勾选 **"Desktop development with C++"**（使用 C++ 的桌面开发）
2. 点击 **"安装"** 按钮
3. 等待安装完成（约 6-8 GB，需要 10-30 分钟）
4. **重启电脑**

> ⚠️ 注意：必须选择 "Desktop development with C++" 工作负载，否则 Tauri 无法编译

---

## 步骤 2：安装 Rust

### 方法一：使用 winget（推荐）

以**管理员身份**运行 PowerShell：

```powershell
winget install Rustlang.Rust.MSVC
```

### 方法二：手动安装

1. 访问：https://rustup.rs
2. 下载 `rustup-init.exe`
3. 运行安装程序
4. 选择默认选项（按 `1` 然后回车）
5. 安装完成后，**重启终端**

### 验证安装

打开新的终端，运行：

```bash
rustc --version   # 应显示 rustc 1.xx.x
cargo --version   # 应显示 cargo 1.xx.x
```

---

## 步骤 3：验证完整环境

打开新的终端，依次运行：

```bash
# 检查 Node.js
node -v
# 输出: v20.19.5

# 检查 Rust
rustc --version
# 输出: rustc 1.xx.x (xxxxxxx 20xx-xx-xx)

cargo --version
# 输出: cargo 1.xx.x

# 检查 MSVC（可选，需要在 Developer Command Prompt 中）
cl
# 输出: Microsoft (R) C/C++ Optimizing Compiler Version ...
```

---

## 步骤 4：集成 Tauri 到项目

### 安装 Tauri CLI

```bash
cd d:/projiect/Wiset/frontend
npm install -D @tauri-apps/cli@latest
```

### 初始化 Tauri

```bash
npm run tauri init
```

按提示填写：

```
✔ What is your app name? wiset
✔ What should the window title be? Wiset
✔ Where are your web assets located? ../dist
✔ What is the url of your dev server? http://localhost:5173
✔ What is your frontend dev command? npm run dev
✔ What is your frontend build command? npm run build
```

---

## 步骤 5：开发与打包

### 开发模式

```bash
npm run tauri dev
```

- 自动启动 Vite 开发服务器
- 打开应用窗口，支持热重载
- 首次启动需要编译 Rust 代码（较慢）

### 打包成 exe

```bash
npm run tauri build
```

输出位置：
- MSI 安装包：`src-tauri/target/release/bundle/msi/`
- NSIS 安装包：`src-tauri/target/release/bundle/nsis/`
- exe 文件：`src-tauri/target/release/`

---

## 常见问题

### Q: 运行 `tauri build` 报错 "failed to run light.exe"

需要启用 VBSCRIPT 功能：
1. 打开 **设置** → **应用** → **可选功能** → **更多 Windows 功能**
2. 找到 **VBSCRIPT** 并勾选
3. 重启电脑

### Q: Rust 编译很慢

首次编译需要下载和编译依赖，后续增量编译会快很多。可以使用以下方法加速：

```bash
# 使用国内镜像（可选）
# 在 ~/.cargo/config 中添加：
[source.crates-io]
replace-with = 'ustc'

[source.ustc]
registry = "https://mirrors.ustc.edu.cn/crates.io-index"
```

### Q: 找不到 WebView2

Windows 11 通常已预装 WebView2。如果缺失：

1. 访问：https://developer.microsoft.com/en-us/microsoft-edge/webview2/
2. 下载 **Evergreen Bootstrapper**
3. 运行安装

---

## 参考链接

- [Tauri 官方文档](https://v2.tauri.app/)
- [Tauri 环境要求](https://v2.tauri.app/start/prerequisites/)
- [Rust 安装指南](https://rustup.rs/)
- [Visual Studio Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/)
