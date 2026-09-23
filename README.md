# Jev 聊天助手（Android）

这是一个 Android 11+ 的聊天辅助工具。它通过无障碍服务读取当前聊天窗口，调用 Jev 判断接口和生成模型接口，在悬浮窗中显示对话判断、候选回复和 OCR 结果。程序只把选中的回复填入输入框，不自动发送消息。

## 1. 系统要求

| 项目 | 要求 |
|---|---|
| Android | 11+（`minSdk 30`） |
| CPU | `arm64-v8a` |
| 编译 SDK | Android SDK 35 |
| Build Tools | 35.0.0 |
| JDK | 17 |
| Gradle | 8.9（项目自带 wrapper） |

## 2. 功能范围

- 读取微信、QQ、X、飞书当前聊天窗口。
- 判断对方真实意图、危险等级、需求、最佳动作、是否应立即回复。
- 生成 3 条候选回复，并由判断模型重新排序。
- 点击候选回复可复制或填入当前输入框。
- 支持本地知识库、联系人档案、联系人关系和可选聊天历史。
- 树里读不到正文时，用本地 ML Kit 中文 OCR 兜底。
- 任意 App 可在悬浮窗菜单中手动执行一次“截屏识别”。

明确不做的行为：

- 不自动发送消息。
- 不点击转账、红包、收款相关控件。
- 不 Hook、不注入、不修改目标 App 安装包、不读取目标 App 数据库。
- 不使用 Root、Xposed、LSPosed 或 Shizuku。

## 3. 平台支持

| App | 采集方式 | 当前状态 |
|---|---|---|
| 微信 Android | 无障碍节点，服务类名使用系统风格伪装 | 8.0.78 实测可用；微信更新后可能失效 |
| QQ Android | 读取 `mjn` 等节点 ID | 9.3.50 群聊实测；一对一按相同结构推断 |
| X / Twitter 私信 | 解析 Compose 节点的 `content-desc` | 12.25.2 中文界面实测；英文界面未验证 |
| 飞书 / Lark | 读取气泡矩形，再用 ML Kit OCR | 已做真机验证 |
| 其他 App | 悬浮窗菜单手动“截屏识别一次” | 整屏 OCR，不区分我方和对方 |

## 4. 工作原理

```text
聊天窗口
  -> ChatAppAdapter 读取标题和消息
  -> ChatSnapshot
  -> ContextBuilder 匹配联系人、关系、笔记、历史
  -> JudgeClient 调判断接口
  -> ReplyClient 生成候选回复
  -> JudgeClient 调判断接口排序
  -> OverlayController 显示结果
  -> ACTION_SET_TEXT 或剪贴板粘贴填入输入框
```

主要模块：

- `capture/ChatAppAdapter.kt`：每个聊天 App 一个适配器。
- `capture/ChatCaptureService.kt`：无障碍服务、前台包分发、分析调度。
- `capture/ocr/`：无障碍截屏、限频退避、ML Kit 中文 OCR。
- `jev/JudgeClient.kt`：判断和候选排序。
- `jev/ReplyClient.kt`：生成候选回复。
- `jev/VisionClient.kt`：OpenAI 兼容视觉接口。
- `jev/HttpJson.kt`：统一 POST、重试、错误归类、OpenCode Session 头。
- `core/kb/`：知识库、联系人、关系、历史记录和上下文预算。
- `overlay/OverlayController.kt`：悬浮球和结果面板。

适配新聊天 App：

1. 实现 `ChatAppAdapter`，提供 `pkg` 和 `extract(root, resources)`。
2. 在 `ChatCaptureService.adapters` 中注册。
3. `extract` 返回 `null` 表示不在聊天窗口。
4. 返回空消息列表表示在聊天窗口，但节点树没有正文，允许走 OCR 兜底。
5. 判断、回复、排序、悬浮窗和填入逻辑不需要改。

## 5. 模型接口

设置页分为三路接口。地址、密钥、模型分别可配。

### 5.1 判断接口

用途：判断意图、危险等级、需求、最佳动作、是否回复，并排序候选回复。

| 预设 | 地址 | 默认模型 |
|---|---|---|
| OpenRouter | `https://openrouter.ai/api/alpha/decisions` | `typesafe/jev-1.13` |
| TypeSafe 直连 | `https://api.typesafe.ai/v1/systemone` | `jev-latest` |
| OpenCode Zen | `https://opencode.ai/zen/v1/systemone` | `jev-1.13-free` |
| 自定义 | 用户填完整 URL | 用户填模型名 |

请求体统一为：

```json
{
  "model": "模型名",
  "state": {},
  "questions": {}
}
```

判断结果从响应体的 `answers` 读取。

### 5.2 回复接口

用途：生成 3 条候选回复。

| 预设 | Base URL | 默认模型 |
|---|---|---|
| OpenRouter | `https://openrouter.ai/api/v1` | `deepseek/deepseek-chat-v3.1` |
| DeepSeek 官方 | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | `deepseek-v4.1-flash` |
| 通义兼容 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |

客户端会拼接 `/chat/completions`。

如果暂时没有配置 Jev，或者 Jev 请求失败，可以在设置中开启“Jev 不可用时仍用回复接口生成候选”。开启后回复接口会继续生成 3 条候选，但不提供意图、危险等级和 Jev 排序，面板会明确标注“未排序”。

### 5.3 视觉接口

| 预设 | Base URL | 默认模型 | 说明 |
|---|---|---|---|
| OpenRouter | `https://openrouter.ai/api/v1` | `qwen/qwen2.5-vl-72b-instruct` | 支持 `image_url` |
| DeepSeek V4.1 | `https://api.deepseek.com/v1` | `deepseek-v4.1-flash` | 支持 `image_url` |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | `deepseek-v4.1-flash` | 支持 `image_url` |
| 通义兼容 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-vl-max` | 支持 `image_url` |

DeepSeek 的 `deepseek-chat` / `deepseek-reasoner` 是文本模型，不接受 `image_url`；`deepseek-v4.1-flash` 支持多模态图片输入。

### 5.4 密钥继承

- 回复接口密钥为空：继承判断接口密钥。
- 视觉接口密钥为空：继承回复接口密钥，再继承判断接口密钥。
- Base URL 和模型不会自动继承，必须切换到对应预设或手动填写。

### 5.5 OpenCode Session

所有请求到 `opencode.ai` 及其子域名的接口，会自动附加：

```text
x-opencode-session
x-session-affinity
x-client-request-id
x-session-id
```

Session ID 是持久化的 8 位字母数字，保存在 App 私有 SharedPreferences 中，字段名为 `opencode_session_id`。

## 6. 上下文规则

判断和回复默认使用当前屏幕最近 10 条消息。

如果满足以下两个条件，会额外注入历史：

1. 设置中开启“记录聊天历史”。
2. 当前会话标题匹配到联系人档案。

历史规则：

- 每个联系人本地最多保存 300 条。
- 默认注入最近 30 条，设置范围 `0–100`。
- 注入前会剔除当前屏幕已经显示过的相同消息。
- 历史和命中的非常驻笔记共享 1500 字预算；超预算时先删最旧历史，再删笔记。
- 常驻笔记不占这 1500 字预算。

关系规则：

- 匹配到联系人时，使用联系人档案中的“关系”。
- 联系人没有填写关系时，回退到设置页的“默认关系”。
- 没有匹配到联系人时，使用设置页的“默认关系”。

笔记匹配规则：

- 在会话标题和最近 6 条消息中做标签、标题包含匹配。
- 最多注入 5 条命中的非常驻笔记。
- 常驻笔记每次分析都注入。

## 7. 安装与使用

### 7.1 安装 APK

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

仓库中的 `apk/jev-assistant-v1.3-release.apk` 对应已提交的 v1.3 release 包，不包含当前未发布的源码改动。需要最新功能时，请按第 8 节自行构建 Debug 或重新签名 Release。

Debug 包和 release 包签名不同，不能相互覆盖安装；切换前需要先卸载旧包，卸载会清除 App 数据和无障碍授权。

### 7.2 开启权限

首次使用需要开启：

1. 无障碍服务。
2. 悬浮窗权限。
3. 通知权限（Android 13+）。
4. 自启动和省电无限制。小米 / HyperOS 建议同时开启。

升级到 1.3 后，如果 OCR 截屏报错，需要把无障碍服务关闭再重新打开一次，让 `canTakeScreenshot` 配置重新绑定。

### 7.3 配置接口

1. 打开“设置”。
2. 填写判断接口的地址、密钥和模型。
3. 回复和视觉可以留空继承密钥；地址和模型需要按供应商选择。
4. 每张卡都可以单独点击“测试判断”“测试回复”“测试视觉”。
5. 如果使用 OpenCode，判断选 OpenCode Zen，回复和视觉选 OpenCode Go，只填一把 OpenCode Key 即可。
6. 如果暂时不配置 Jev，开启“Jev 不可用时仍用回复接口生成候选”，即可只使用回复接口。

### 7.4 悬浮窗操作

- 单击悬浮球：展开或收起结果面板。
- 拖动悬浮球：调整位置，位置会记忆。
- 长按悬浮球：打开菜单。
- “截屏识别一次”：对当前屏幕执行一次 OCR 分析。
- “设置当前会话联系人”：打开联系人编辑页，预填当前会话标题和来源 App；可同时填写关系、备注和别名。
- “打开设置”：进入设置页。
- “隐藏助手（本次）”：隐藏悬浮窗，不修改无障碍授权。

结果面板中的操作：

- “复制”：把候选回复复制到剪贴板。
- “填入”：把候选回复填入当前输入框，不会点击发送。
- “重新分析”：对当前快照重新请求判断和候选回复。

### 7.5 知识库、联系人和关系

入口：设置 -> 分析 -> 知识库与联系人。

笔记字段：

- 标题
- 正文
- 标签
- 常驻
- 启用状态

联系人字段：

- 姓名
- 别名，每行一个
- 关系
- 备注

联系人关系优先于设置页的默认关系。一个联系人可以绑定一个独立关系，不会再出现一个全局关系覆盖所有人的问题。

### 7.6 聊天历史

- 默认关闭。
- 开启后只写入 App 私有目录 `filesDir/kb/logs/`。
- 设置页可一键清空知识库和历史。
- 清除历史不会删除密钥、白名单等设置。

### 7.7 OCR

- 飞书等自绘控件会读取气泡矩形，再对每个矩形做本地 OCR。
- 无障碍树读不到正文的其他 App 可以在悬浮窗菜单中手动整屏 OCR。
- 图片在本地 ML Kit 处理，不上传。
- `FLAG_SECURE` 页面无法截屏。
- 长消息超出屏幕的部分无法识别。

## 8. 构建与部署

### 8.1 环境变量

```powershell
$env:JAVA_HOME = "D:\AndroidToolchain\jdk-17"
$env:ANDROID_HOME = "D:\AndroidToolchain\sdk"
$env:ANDROID_SDK_ROOT = "D:\AndroidToolchain\sdk"
$env:GRADLE_USER_HOME = "D:\path\to\gradle-home"
```

### 8.2 Debug 构建

```powershell
.\gradlew.bat assembleDebug --no-daemon --console=plain
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 8.3 Release 构建

Release 签名配置从仓库外的 properties 文件读取：

```properties
storeFile=D:/keys/jev-release.jks
storePassword=******
keyAlias=jev-release
keyPassword=******
```

通过环境变量指定路径：

```powershell
$env:JEV_KEYSTORE_PROPS = "D:\keys\jev-release.properties"
.\gradlew.bat assembleRelease --no-daemon --console=plain
```

输出：

```text
app/build/outputs/apk/release/app-release.apk
```

没有签名配置时，release 构建不会使用正式签名，可能生成未签名包。不要把 keystore、密码或 properties 文件提交到仓库。

### 8.4 Windows 路径限制

Android Gradle Plugin 会拒绝非 ASCII 项目路径。项目包含中文路径时，Debug/Release 构建可能直接失败。Windows 上请把项目放在纯 ASCII 路径，例如：

```text
D:\AndroidBuild\jev-android
```

### 8.5 检查 APK

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose app\build\outputs\apk\debug\app-debug.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump badging app\build\outputs\apk\debug\app-debug.apk
```

## 9. 数据与隐私

- 项目没有自建中转服务器。
- 聊天内容只在你触发分析时发送给你配置的模型接口。
- API Key 保存在 App 私有 SharedPreferences 中，未单独加密，依赖 Android 应用沙箱和 `allowBackup=false`。
- 知识库、联系人、关系和历史保存在 `filesDir/kb/`。
- 聊天正文不会写入 logcat；日志会记录消息数量、文本长度、会话标题和错误类型。
- API 错误会显示服务端返回文本的前 120 字，便于定位 401、403、429 等问题。
- 历史记录默认关闭。

## 10. 已知限制

- 微信节点混淆依赖系统风格的服务类名，微信版本更新后可能失效。
- 国产 ROM 可能冻结后台进程，导致短时间读不到消息。
- 群聊按一对一场景分析，联系人和关系判断可能不准确。
- X 只验证过中文界面。
- 飞书 OCR 只能识别当前屏幕可见内容，可能有错字。
- Jev 训练语言以英文为主，中文效果建议用真实对话校准。
- APK 只包含 `arm64-v8a`。
- Debug 包和 release 包签名不同，不能覆盖安装。

## 11. 故障排查

### 11.1 读不到消息

1. 确认无障碍服务仍在系统设置中开启。
2. 确认悬浮窗权限已开启。
3. 确认当前聊天 App 在前台。
4. 小米 / HyperOS 开启自启动和省电无限制。
5. 微信升级后尝试关闭再打开无障碍服务。

### 11.2 无障碍授权丢失

应用内“关闭助手”只修改处理开关，不会关闭系统无障碍服务。常见原因是：

- 卸载或重装 App。
- Debug 包和 release 包签名不同，安装前必须卸载。
- 系统设置中强行停止或清除数据。
- 系统更新、重启或 OEM 安全策略重置。
- 无障碍服务崩溃后被系统关闭。

### 11.3 接口报错

1. 在设置页使用对应的测试按钮。
2. 检查 Base URL、模型名和密钥。
3. 检查 401、403、429、503 等错误文本。
4. OpenCode 用户确认判断使用 OpenCode Zen，回复和视觉使用 OpenCode Go。

### 11.4 OCR 失败

1. 升级到 1.3 后重新绑定无障碍服务。
2. 确认页面没有 `FLAG_SECURE`。
3. 确认系统允许无障碍服务截屏。
4. 截图过于频繁时会触发限频和退避，稍后重试。

### 11.5 填入失败

填入逻辑只调用 `ACTION_SET_TEXT` 或剪贴板粘贴，不会点击发送。失败时会自动复制到剪贴板，可以长按输入框手动粘贴。

## 12. 项目结构

```text
app/src/main/java/com/jev/probe/
  capture/          无障碍采集、适配器、前台保活、OCR
  core/             配置和数据模型
  core/kb/          知识库、联系人、关系、历史和上下文
  jev/              判断、回复、视觉和 HTTP 客户端
  overlay/          悬浮窗
  MainActivity.kt   首页和权限引导
  SettingsActivity.kt
  KnowledgeActivity.kt

app/src/main/res/
  drawable/         启动图标前景和单色图标
  mipmap-anydpi-v26/自适应图标
  values/           字符串、主题、图标背景色

tools/jev/          Jev 题目集和校准脚本
apk/                release APK
docs/               设计和验收文档
```

图标 SVG 源稿：`docs/images/ic_launcher_owl.svg`

## 13. 许可

项目使用 MIT License。分发、修改或商用请保留 `LICENSE` 和 `NOTICE`，不要用项目名称或域名暗示原作者背书。
