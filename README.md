# xxtT · xxt-Tool

一个轻量的 Android 小工具：登录**学习通**或**雨课堂**后，把作业和考试汇总到四个栏目里，按剩余时间排好，省得每次翻五六个入口；2.5.0 起再加一个本地课程表。

> 中文名（仅 GitHub 展示用）：学习通小工具

- 体积约 1–2 MB（release），无后台常驻、无推送、无广告
- 权限只有网络和安装更新（`REQUEST_INSTALL_PACKAGES`）
- 支持 Android 7.0（API 24）及以上
- 纯本地运行，登录态只存在本机（Android Keystore 加密）
- 安装包命名统一为 `xxtT-v版本号.apk`；GitHub Release 标题统一为 `v版本号`

当前版本：**2.7.2**（versionCode 20702），版本规则见 [VERSIONING.md](VERSIONING.md)。

## 功能

| 栏目 | 规则 |
|---|---|
| **未完成** | 没交且未到截止的。默认按截止时间由近到远，可切换排序（近→远 / 远→近 / 按课程 / 按类型）。右侧显示剩余时间，颜色分级默认：绿 ≥5 天、橙 ≥1 天、红 <24 小时，可在「个人 → 颜色提醒」改成 7/3 等 |
| **待批阅** | 已交、老师未批的，默认只看最近 7 天交的 |
| **已完成** | 老师已批阅的（7 天内，带分数）；以及「待批阅」超过 7 天后移入的，标橙色「超期未批」，再保留 7 天后自动隐藏 |
| **已过期** | 过了截止还没交的，永久保留、不自动清理 |

其它：

- 顶部卡片显示「距最近截止日期的作业」大倒计时：`X天X时`，不足 24 小时显示 `X时`，不足 1 小时显示「小于1时」，颜色随剩余时间分级
- 「待批阅 7 天 / 已完成 7 天」两个窗口只在「待批阅 / 已完成」栏目下可调（1–365），其它栏目下置灰
- 单条**长按 → 移除**，可在「个人」页恢复
- 学习通任务：点任意一条**直接打开学习通页面**，页内的「继续答题 / 查看详情 / 修改答案」都能正常点（内置了学习通的 jsBridge 桥接）；作业页长按标题可查看「页面诊断」
- 雨课堂任务：点任意一条打开对应课程页，再点具体活动；雨课堂不支持应用内更新，更新靠 GitHub Release 手动下载
- 「个人 → 退出登录」清空本机账号、课程与作业缓存，回到空白的未登录界面，可换号登录
- 启动每天检查一次 GitHub 更新；「个人 → 检查更新」可手动检查，弹窗选择「立即更新 / 以后再说 / 打开网页」
- 下载完成后校验包名、versionCode 和签名，再交给系统安装器覆盖安装（安装完成后手动打开 App）
- 下拉刷新；打开 App 自动刷新
- 「个人 → 抓取日志」可查看每一步请求结果，一键复制，方便排查接口变动
- 支持学习通 / 雨课堂双账号同时登录，任务合并到同一套四栏，条目和顶部卡片都标注「学习通 / 雨课堂」；「个人」页可分别登录、分别退出，底部「退出登录」同时退出两个账号
- 雨课堂登录默认 `https://www.yuketang.cn/web`，服务器跟随网页里用户的选择，不再单独设「更换服务器」

## 课程表（2.5.0+）

底部「课表」进入，和作业看板相互独立：

- **默认 13 节作息**：08:00–21:00，上午/中午/下午/晚上分段；可在「课表设置 → 节次与时间」逐节改，或按“每节时长 + 课间”批量生成。
- **周视图**：周一至周日 × 节次，左侧固定显示节次和起止时间；顶部显示第 N 周、月份、日期，今天高亮。
- **周次**：以「第 1 周周一」为锚点；可手动选日期，也可输入“本周是第几周”反推；默认预置 2026-09-07，可随时校正。
- **课程**：同一门课可加多个时间段，分别设置周次、星期、节次、校区、地点、教师；支持单双周、跳周、前后几周换教室/换教师/没课。
- **快速加课**：点空白小方格第一次出现加号，再点同一格打开快速添加；开始节次固定为所点格子，可在「上到第几节」里选结束节次（例如点 10:00 的格子、上到第 4 节，课表按 10:00–11:40 整块更新）。保存前会检查时间冲突，冲突时列出冲突课程并阻止保存。
- **课程块**：显示课程名称、地点、教师；名称字号最大，整体已放大。
- **详情**：点课程弹出多张时间段卡，当前周在前，非本周黑色药丸；每段可编辑。
- **未排课**：没有时间段的课程单独列出，点进去补时间段。
- **外观**：暖米白 + 陶土强调色；5 个强调色可切换；课程之间不做颜色区分；首页/个人页也已统一到这个风格。
- **导入**：支持教务系统导出的 Excel（`.xlsx`）、CSV、HTML 和文字型 PDF 课表，先预览课程/时间段/周次/地点/教师和警告，再选择覆盖或合并导入。
- **备份**：「课表设置 → 导出课表 JSON」可备份/换机；导入会覆盖当前课表。

> 扫描件 / 图片 OCR 和教务系统网页抓取在后续版本。

## 版本规则

采用 `MAJOR.MINOR.PATCH`，应用名始终不变：

- **MAJOR**：不兼容变更（数据结构、minSdk、包名/签名、大改版），同时清零 MINOR/PATCH；
- **MINOR**：新增用户可见功能，清零 PATCH；
- **PATCH**：只修 Bug/文案/样式。

`versionCode = MAJOR × 10000 + MINOR × 100 + PATCH`；当前 2.7.2 = 20702。每次交付至少 +1 PATCH。完整规则和发布流程见 [VERSIONING.md](VERSIONING.md)，每个版本的改动见 [CHANGELOG.md](CHANGELOG.md)。

## 原理

学习通没有开放 API，本项目通过登录态直接请求它的 H5 页面：

| 数据 | 接口 | 说明 |
|---|---|---|
| 课程列表 | `mooc1-api.chaoxing.com/mycourse/backclazzdata` | JSON，一次拿全 |
| 作业列表 | `.../phone/moocAnalysis/workList?courseId&classId` | HTML，`status` 属性：1未交 2已过期 3待批阅 4已完成 |
| 考试列表 | `.../phone/moocAnalysis/examList?courseId&classId` | HTML，表格里直接带得分 |
| 作业详情 | `/work/phone/task-work?...` | 开始/截止时间、分数 |
| 考试详情 | `/exam/phone/task-exam?...` | 提交时间、成绩 |

官方 App 的聚合接口 `gas/taskmsg` 对第三方 UA 一律 403（WAF），所以不走那条路。

**关于页面内的按钮**：作业/考试页面里的「继续答题 / 查看详情 / 修改答案」不是普通链接，而是调用学习通自己的 `jsBridge.postNotification('CLIENT_OPEN_URL', ...)` 交给原生 App 打开。本项目在 WebView 里实现了这个桥（`androidjsbridge`），所以点这些按钮能直接进入答题/查看页面。

**关于「提交时间」**：学习通的列表页和详情页都不提供提交时间，因此：

1. 每次刷新对比每条作业的状态，**「未交 → 待批阅」的那一次刷新记为提交时间**，「待批阅 → 已完成」记为批阅时间。用几天后时间窗口就非常准。
2. 第一次见到就已经是待批阅/已完成的（历史数据），退回「发布日期」；发布日期也没有（如「长期有效」）才用首次抓取时间。
3. 因此刚装上的头两天，「已完成」里可能比较少——旧学期的历史条目会被时间窗口挡掉，这正是为了不把几百条陈年作业塞进来。

## 雨课堂（实验性）

雨课堂没有公开 API，本项目走它的网页接口：

- 登录：内嵌 `https://www.yuketang.cn/web`，微信扫码；页面里可切换服务器，登录后读取当前 URL 域名保存。
- 课程：`/v2/api/web/courses/list?identity=2`
- 活动：`/v2/api/web/logs/learn/{classroom_id}?actype=-1&page=0&offset=100&sort=-1`
  - `type=5` 习题/考试（`status` 0未做 1进行中 2待批阅 3已完成，带分数）
  - `type=19` 作业（`score_d` 截止时间，`is_assessed` 判断是否已批阅）
- 课程结束时间：`/v2/api/web/classrooms/{id}?role=5` 的 `class_end`，无显式截止时作为兜底。
- 近期考试：`/api/v3/classroom/on-lesson-upcoming-exam`，用于区分考试/习题。

限制：

- 雨课堂不提供提交/批阅时间，待批阅/已完成的日期用首次同步时间近似。
- `type=19` 作业只有 `is_assessed` 可推断是否已批，无法拿到具体分数。
- 课件/视频/签到当前不展示，只保留作业和考试。
- 雨课堂活动页的真实路由 ID 与日志不一致，点任务先打开课程页。
- 两个数据源各自独立缓存、独立退出；合并后按同一套时间窗口和排序显示。

## 安装

两种方式：

1. **正式 2.7.2 包**：**[`dist/xxtT-v2.7.2.apk`](dist/xxtT-v2.7.2.apk)**（release 签名，与 2.1.x–2.7.1 同一把 key）。可直接覆盖 2.7.1 安装，登录/缓存保留。
2. **开发调试包**：本地运行 `scripts/build.ps1` 生成 `app/build/outputs/apk/debug/app-debug.apk`（版本名 `2.7.2-debug`，debug 签名）。debug 与正式包签名不同，**不能覆盖正式包**；测试请用模拟器或先卸载。

> 安装包命名规范：`xxtT-v版本号.apk`（例如 `xxtT-v2.7.2.apk`）。正式发布包用 `scripts/release.ps1` 生成；`keystore.properties` 与 `signing/*.jks` 不入库。

传到手机安装（需允许「安装未知来源应用」）。

首次打开先选择「学习通登录」或「雨课堂登录」：学习通用手机号/学号 + 密码登录；雨课堂默认打开 `https://www.yuketang.cn/web`，用微信扫码登录，页面里可以自己切换服务器，登录成功后自动按实际服务器抓取。两个账号可以同时登录，也可以只登一个。

## 自己编译

需要 Windows + PowerShell（脚本会下载 JDK 17、Android SDK、Gradle 到项目下的 `toolchain/`，可用环境变量 `XXKG_TOOL_DIR` 改位置）。

```powershell
# 1. 下载工具链（清华 JDK 源 + 腾讯 SDK 源，约 500 MB）
powershell -ExecutionPolicy Bypass -File .\scripts\setup_env.ps1

# 2. 打调试包 / 正式包
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 :app:assembleRelease

# 3. 打正式发布包（签名、复制到 dist/、算 SHA-256）
powershell -ExecutionPolicy Bypass -File .\scripts\release.ps1

# 4. 跑单元测试
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 :app:testDebugUnitTest
```

正式包签名：把 `keystore.properties` 放在项目根目录（已 gitignore），内容：

```properties
storeFile=/path/to/your.jks
storePassword=******
keyAlias=your_alias
keyPassword=******
```

没有这个文件时 release 包不签名，但仍可编译。

## 目录结构

```
app/src/main/java/com/xxkg/board/
├─ MainActivity.kt      主界面（四栏、排序、刷新）
├─ LoginActivity.kt     内嵌学习通登录页
├─ WebActivity.kt       打开学习通/雨课堂页面 + jsBridge 桥接（继续答题 / 修改答案）
├─ YtApi.kt             雨课堂：登录态 / 课程 / 活动 / 状态映射
├─ SettingsActivity.kt  个人页（时间窗口、颜色提醒、日志、恢复、退出登录、检查更新）
├─ UpdateChecker.kt     检查 GitHub Release / 版本比较
├─ UpdateManager.kt     更新弹窗 / 下载 / 校验 / 安装
├─ XtApi.kt             抓取：课程/作业/考试/详情 + 状态变化追踪
├─ HtmlParsers.kt       H5 页面解析
├─ Logic.kt             时间格式化 + 四栏分类规则
├─ Models.kt            Task / Course
├─ Prefs.kt             本地存储
├─ Sec.kt               AES-GCM（Android Keystore）
├─ Cookies.kt           WebView Cookie 收割
│
├─ TimetableActivity.kt 课表主页面（周切换 / 详情 / 未排课）
├─ TimetableView.kt      课表网格自绘（课程块 / 今天 / 当前时间线）
├─ TimetableHeaderView.kt 月份 + 星期/日期
├─ TimetableModels.kt    课表数据模型 + JSON
├─ TimetableLogic.kt     周次、日期、节次、文案
├─ TimetableStore.kt     timetable.json 读写 / 备份
├─ CourseEditActivity.kt 课程信息 + 时间段列表
├─ SegmentEditActivity.kt 单个时间段编辑
├─ PeriodSettingsActivity.kt 节次与时间
├─ TermSettingsActivity.kt 学期与周次
├─ TimetableSettingsActivity.kt 课表设置
├─ WeekChipsView.kt      周次选择小方块
└─ Palette.kt            课表新 UI 配色

app/src/test/           单元测试（规则引擎 + 解析器 + 课表周次 + 版本号）
app/src/debug/          仅调试包的登录态注入入口（不进正式包）
scripts/                环境准备、编译、接口探测脚本
VERSIONING.md           版本规则
CHANGELOG.md            更新日志
RELEASE_NOTES.md        当前 Release 说明
```

## 说明与限制

- 仅供个人学习、自用。不隶属超星/学习通官方，也未获得其授权；请遵守学校与平台的使用条款，不要用于刷课、代做等违规用途。
- 作业看板只读展示，不会替你做作业、交作业。
- 课程表 2.7.2 支持手动维护、JSON 备份和 Excel/CSV/HTML/PDF（文字型）导入；扫描件 OCR 和教务系统网页抓取在后续版本。
- 学习通改版会导致页面选择器失效，需要跟着改；`个人 → 抓取日志` 能看到是哪一步失败。
- 截止时间需要逐条打开作业详情页（只打开未交/过期/近期完成的，通常 20–30 个请求），网络慢时会多等几秒。

## License

[MIT](LICENSE)

