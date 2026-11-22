# Glow Android 版 · 架构决策

| 项 | 内容 |
| --- | --- |
| 目标形态 | **纯客户端**：Android App + 现有 Mac mini 模型服务，无任何自建后端 |
| 技术选型 | Kotlin + Jetpack Compose（单模块） |
| 与 DSH 版关系 | 同一套产品规则，重写实现；用金标准向量保证不走样 |
| 文档状态 | 设计定稿，尚未开始编码 |

---

## 1. 形态确认

```
┌──────────────┐   Tailscale（或同局域网）   ┌────────────────────┐
│ Android App  │ ──────────────────────────► │ Mac mini           │
│ 全部业务逻辑  │   HTTP  · 无自建后端         │ :9741 出图服务      │
│ 本地存储      │ ◄────────────────────────── │ :9739 提示词服务    │
└──────────────┘                              └────────────────────┘
```

**没有中间层**。DSH 版里 Host 承担的四件事——指令合成、任务排队、结果落盘、设置存储——全部搬进 App 内部。桌面端从此可选：手机不依赖电脑是否开机，只依赖 Mac mini 上的两个模型服务。

代价必须说清：**模型服务本身没有任何认证**。今天 DSH 也是无认证直连，换成手机后这个事实不变——同一 tailnet 内能访问它的人，就能直接调它。这是 Mac mini 侧的部署姿态，不是 App 能解决的。App 能做的只是不额外暴露任何东西。

---

## 2. 三个必须处理的设计点

这三条是与 DSH 版**最大的行为差异**，不是细节。

### 2.1 任务必须跑在前台服务里

DSH 版有个天然优势：Host 是常驻进程，用户切走页面任务照跑。App 没有这个优势——一次精修 220–380 秒，用户很可能切去回微信。Android 会挂起后台进程，**连接一断，服务端算完的图也拿不回来**。

因此：**每次提交生成，启动一个前台服务**（`dataSync` 类型），带常驻通知显示进度，任务结束即停。

- 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`（API 33+ 需运行时申请）
- 通知文案直接给结果价值：「正在精修 1 张 · 已等 1 分 20 秒」
- 服务里持有 HTTP 调用，**不用 WorkManager**（其最小周期 15 分钟，不适合进度轮询；且长任务仍需前台服务）

### 2.2 网络超时必须显式放宽

DSH 版踩过的坑在这里会重演，而且第一次就会踩死：

| 客户端 | 默认超时 | 后果 |
| --- | --- | --- |
| Node `fetch`（undici） | 300 秒响应头 | DSH 版实测第 306 秒失败 |
| **OkHttp** | **读超时 10 秒** | 1024² 精修必挂 |

所以 OkHttp 必须显式配置：

```kotlin
OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.MINUTES)   // 上传 2 张大图
    .readTimeout(15, TimeUnit.MINUTES)    // 等出图
    .callTimeout(0, TimeUnit.MILLISECONDS) // 不设整体上限，由业务层控制
    .build()
```

图片下载（结果与参考图副本）用**另一个客户端**，读超时 60 秒即可——不要把长超时套到 `Coil` 的加载上。

### 2.3 明文 HTTP 会被系统拦掉

模型服务是纯 HTTP。Android 9 起默认禁止明文流量，不处理会直接 `CLEARTEXT communication not permitted`。

- 加 `android:usesCleartextTraffic="true"`（服务地址由用户配置，无法预先列举域名）
- 传输安全由 Tailscale 的 WireGuard 承担，与 DSH 版一致

---

## 3. 技术选型

| 关注点 | 选择 | 理由 |
| --- | --- | --- |
| UI | Jetpack Compose + Material3 | 原生手势/相机/分享；复用 DSH 版的信息架构，不直接复用它 98KB 的 JSX |
| HTTP | OkHttp + kotlinx.serialization | 可控超时；无 Node 那套隐藏默认值 |
| 图片加载 | Coil | 画廊缩略图与全屏查看 |
| 图片处理 | `BitmapFactory.inSampleSize` 降采样 | 手机原图 3–8 MB，降采样到长边 ≤1536 后上传（模型本就在 1024² 附近训练，无损画质且上传快数倍） |
| 本地存储 | App 私有目录 + sidecar JSON | **沿用 DSH 版的落盘方案**：一张图 + 一个同名 `.json`。不引入 Room——数据量在千条量级，目录扫描足够，且迁移/备份透明 |
| 选图 | `PickVisualMedia`（系统相册选择器） | 不需要存储权限 |
| 导出到相册 | MediaStore 插入 | Q+ 无需权限 |

**不采用**三种方案及原因：

- **WebView 套壳复用 client.js**：省 UI 工作量，但要在本地起 HTTP 或写 fetch 桥，且 App 生命周期问题一个都不会少。产品长期维护成本高于重写。
- **嵌入式 JS 引擎跑现有 Host**：需要为 `node:http`/`node:fs`/`node:crypto` 写一整套 shim，等于自造运行环境。
- **保留一个中间服务**：与「无后端」目标直接冲突。

---

## 4. 模块划分

```
app/src/main/java/dev/haljk/glow/
├─ core/                     ← 纯 Kotlin，无 Android 依赖，可 JVM 单测
│  ├─ Preset.kt              9 个精修预设 + mayChange + 说明文案
│  ├─ Role.kt                6 种参考图用途 + 指令句式
│  ├─ PreserveClause.kt      冻结清单生成（总表 − mayChange）
│  ├─ Compose.kt             指令合成：预设 + 角色 + 补充 + 冻结
│  ├─ SizeFit.kt             比例继承与像素预算拟合
│  ├─ Density.kt             快速/推荐/高清 → 像素预算
│  └─ Job.kt                 任务状态机（queued/running/done/failed）
├─ data/
│  ├─ ModelClient.kt         出图服务、提示词服务（OkHttp，双客户端）
│  ├─ GalleryStore.kt        sidecar JSON 读写、目录扫描、归档标记
│  ├─ FavoriteStore.kt       常用提示词（上限 5）
│  └─ SettingsStore.kt       服务地址、超时、输出目录
├─ service/
│  └─ GenerationService.kt   前台服务，执行任务并推进通知
└─ ui/
   ├─ WorkbenchScreen.kt     三场景主界面
   ├─ ViewerScreen.kt        全屏查看 + 并排/滑动对比
   ├─ ArchiveScreen.kt       归档箱
   ├─ SettingsScreen.kt      模型服务、常用提示词
   └─ theme/
```

`core/` 刻意**不依赖 Android**：它承载全部产品规则，用 JVM 单元测试就能验证，不需要真机或模拟器。

---

## 5. 与 DSH 版的一致性保障

这是本次移植最关键的工程手段。

`tools/make-conformance-vectors.js` 从**当前运行的 DSH 版**录下 44 条金标准：

| 类别 | 条数 | 来源 |
| --- | --- | --- |
| 指令合成 | 17 | 逐个调用 `/api/glow/preview`，记录完整输出字符串 |
| 尺寸拟合 | 24 | 8 种原图比例 × 3 档清晰度 |
| 拒绝用例 | 3 | 缺指定用途、无参考图、未知预设 |

产物在 `android/app/src/test/resources/conformance/vectors.json`。Kotlin 侧 `core/` 的单测逐条比对 `expected`，**必须字符串完全一致**。

这样做的意义：产品规则（哪几项被冻结、角色句式、比例取整、拒绝条件）不靠人眼 review 保证，而由已发布实现当裁判。任何一处走样，单测立即红。

---

## 6. 与 DSH 版的行为差异

| 项 | DSH 版 | Android 版 |
| --- | --- | --- |
| 任务执行位置 | Host 进程（常驻） | App 内前台服务（用户可见通知） |
| 切走/锁屏 | 任务继续 | 任务继续（前台服务保障） |
| 关掉应用 | 任务中断，已出图保留 | 同 |
| 目录结构 | `~/Pictures/glow/` | App 私有 `files/gallery/`，导出走 MediaStore |
| 设置位置 | `~/.glow/settings.json` | App 私有 `files/settings.json` |
| 归档 | sidecar `archived` 字段 | **完全一致** |
| 常用提示词 | 输出目录 `favorites.json`，上限 5 | **完全一致** |
| 结果列表 | 目录扫描 | **完全一致** |
| 认证 | 无（仅本机访问） | 无（模型服务本身无认证） |

---

## 7. 风险清单

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 厂商杀后台（国产 ROM 尤甚） | 前台服务仍可能被清 | 提供"加入白名单"引导；任务失败时明确提示而非静默 |
| Tailscale 被系统断开 | 提交直接失败 | 提交前做连通性预检，失败时给出可操作提示 |
| 上传体积 | 2 张 8 MB 原图经 base64 达 21 MB | 强制降采样到长边 ≤1536；改为 multipart 直传二进制而非 base64（服务端本就吃 multipart） |
| 服务单并发 | 手机与桌面同时用会撞 409 | 保留退避重试（3 次，间隔 20 秒） |
| 存储增长 | 参考图副本会让占用翻倍 | 提供"不留参考图副本"开关，默认保留 |
| 无工具链 | 本机无 SDK，无法构建 | 见 §8 |

---

## 8. 构建环境现状

| 项 | 状态 |
| --- | --- |
| JDK | ✅ Corretto 21.0.10 |
| Android SDK | ✅ platform 36 / build-tools 36.0.0 / platform-tools 37.0.3（`C:\Android\Sdk`） |
| Gradle | ✅ 9.1.0（`C:\gradle`，官方源下载不动，改用镜像） |
| AGP / Kotlin | ✅ 9.0.1 / 2.3.20 |
| 模拟器 | ⚠️ 已安装并建好 AVD `glow`，但 x86_64 需要虚拟化驱动，安装需管理员权限 |
| 单测 | ✅ 26 个用例全绿（含 Robolectric 渲染的界面测试） |
| APK | ✅ 已产出（12.14 MB） |

路径含中文会让 AGP 的测试工作进程读不到类路径，因此构建在
`C:\glow-build\android` 的镜像副本上进行，由 `tools/build-android.ps1` 自动完成。
细节见 [../android/BUILD-NOTES.md](../android/BUILD-NOTES.md)。

---

## 9. 里程碑

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| M0 | 工具链就绪 | `./gradlew test` 能跑 |
| M1 | `core/` 移植（预设、角色、冻结、合成、尺寸） | **44 条金标准全绿** |
| M2 | `data/` + 设置页 + 连通性预检 | 真机连上 Mac mini，探活通过 |
| M3 | 场景一（文字生图）+ 前台服务 + 画廊 | 真机出图，切后台回来结果仍在 |
| M4 | 场景二（单图精修）+ 对比查看 | 换背景实测身份保持 |
| M5 | 场景三（多图合成）+ 归档 + 常用提示词 | 与 DSH 版行为一致 |
| M6 | 打磨（分享、导出相册、白名单引导） | 可日常使用 |

M1 是本阶段唯一能脱离真机验证的部分，也是最该先做的部分——产品规则一旦走样，后面全是返工。
