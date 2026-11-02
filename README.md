# 学习通作业看板 · Chaoxing Homework Board

一个轻量的 Android 小工具：登录学习通后，把**作业和考试**汇总到四个栏目里，按剩余时间排好，省得每次在学习通里翻五六个入口。

> A tiny Android dashboard for Chaoxing (学习通): log in once, and see all your homework & exams sorted by deadline — 未完成 / 待批阅 / 已完成 / 已过期.

- 体积约 1 MB，无后台常驻、无推送、无广告
- 只有 `INTERNET` 一个权限
- 支持 Android 7.0（API 24）及以上
- 纯本地运行，登录态只存在本机（Android Keystore 加密）

## 功能

| 栏目 | 规则 |
|---|---|
| **未完成** | 没交且未到截止的。默认按截止时间由近到远，可切换排序（近→远 / 远→近 / 按课程 / 按类型）。右侧显示剩余时间，颜色分级：灰 >3 天、橙 1–3 天、红 <24 小时 |
| **待批阅** | 已交、老师未批的，默认只看最近 7 天交的 |
| **已完成** | 老师已批阅的（7 天内，带分数）；以及「待批阅」超过 7 天后移入的，标橙色「超期未批」，再保留 7 天后自动隐藏 |
| **已过期** | 过了截止还没交的，永久保留、不自动清理 |

其它：

- 顶部卡片显示「距最近截止日期的作业」大倒计时
- 「待批阅 7 天 / 已完成 7 天」两个窗口随时可调（1–365）
- 单条**长按 → 移除**，可在「个人」页恢复
- 点任意一条直接打开学习通对应页面，方便去交作业
- 下拉刷新；打开 App 自动刷新
- 「个人 → 抓取日志」可查看每一步请求结果，一键复制，方便排查接口变动

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

**关于「提交时间」**：学习通的列表页和详情页都不提供提交时间，因此：

1. 每次刷新对比每条作业的状态，**「未交 → 待批阅」的那一次刷新记为提交时间**，「待批阅 → 已完成」记为批阅时间。用几天后时间窗口就非常准。
2. 第一次见到就已经是待批阅/已完成的（历史数据），退回「发布日期」；发布日期也没有（如「长期有效」）才用首次抓取时间。
3. 因此刚装上的头两天，「已完成」里可能比较少——旧学期的历史条目会被时间窗口挡掉，这正是为了不把几百条陈年作业塞进来。

## 安装

到 [Releases](../../releases) 下载 `app-release.apk`，传到手机安装（需允许「安装未知来源应用」）。

首次打开进入学习通登录页，用手机号/学号 + 密码登录（验证码在页面里正常过），登录成功后自动抓取，约 1–3 秒。

## 自己编译

需要 Windows + PowerShell（脚本会下载 JDK 17、Android SDK、Gradle 到项目下的 `toolchain/`，可用环境变量 `XXKG_TOOL_DIR` 改位置）。

```powershell
# 1. 下载工具链（清华 JDK 源 + 腾讯 SDK 源，约 500 MB）
powershell -ExecutionPolicy Bypass -File .\scripts\setup_env.ps1

# 2. 打调试包 / 正式包
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 :app:assembleRelease

# 3. 跑单元测试
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
├─ WebActivity.kt       打开具体作业页面
├─ SettingsActivity.kt  个人页（时间窗口、日志、清理）
├─ XtApi.kt             抓取：课程/作业/考试/详情 + 状态变化追踪
├─ HtmlParsers.kt       H5 页面解析
├─ Logic.kt             时间格式化 + 四栏分类规则
├─ Models.kt            Task / Course
├─ Prefs.kt             本地存储
├─ Sec.kt               AES-GCM（Android Keystore）
└─ Cookies.kt           WebView Cookie 收割

app/src/test/           单元测试（规则引擎 + 解析器）
app/src/debug/          仅调试包的登录态注入入口（不进正式包）
scripts/                环境准备、编译、接口探测脚本
```

## 说明与限制

- 仅供个人学习、自用。不隶属超星/学习通官方，也未获得其授权；请遵守学校与平台的使用条款，不要用于刷课、代做等违规用途。
- 只读展示，不会替你做作业、交作业。
- 学习通改版会导致页面选择器失效，需要跟着改；`个人 → 抓取日志` 能看到是哪一步失败。
- 截止时间需要逐条打开作业详情页（只打开未交/过期/近期完成的，通常 20–30 个请求），网络慢时会多等几秒。

## License

[MIT](LICENSE)
