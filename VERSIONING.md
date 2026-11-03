# 版本规则

学习通小工具（英文名 xxt-Tool，简写 xxtT）使用语义化版本 `MAJOR.MINOR.PATCH`，**应用名始终为「学习通小工具 / xxt-Tool」**，不随版本变化。

## 何时递增

| 位 | 递增条件 | 例子 |
|---|---|---|
| **MAJOR** | 不兼容变更：数据结构、`minSdk`、包名/签名、大改版；同时把 MINOR/PATCH 清零 | 2.0.0 |
| **MINOR** | 新增用户可见功能；把 PATCH 清零 | 1.1.0 |
| **PATCH** | 只修 Bug、文案、样式，不新增功能 | 1.1.1 |

每次交付至少 +1 PATCH；一批新功能 +1 MINOR；不兼容才 +1 MAJOR。

## versionCode

```
versionCode = MAJOR * 10000 + MINOR * 100 + PATCH
```

| 版本 | versionCode |
|---|---|
| 1.0.0 | 10000 |
| 1.1.0 | 10100 |
| 1.1.1 | 10101 |
| 1.2.0 | 10200 |
| 2.0.0 | 20000 |
| 2.1.0 | 20100 |
| 2.2.0 | 20200 |
| 2.2.1 | 20201 |

MINOR/PATCH 取值 0–99；超过 99 时进位到上一位。

## 版本名与调试包

- 正式版本名：`2.1.0`。
- debug 包自动追加后缀：`2.1.0-debug`（`build.gradle.kts` 的 `versionNameSuffix`）。
- 应用名不变；「个人」页底部显示实际版本名。

## 签名与备份

- 正式包用 `keystore.properties` 指向的 release keystore 签名；当前是 `signing/xxkg-release.jks`，alias `xxkg`。
- `signing/*.jks`、`keystore.properties` 已在 `.gitignore` 中，不会进仓库；**不要把这两个文件发给别人或提交**。
- 请把 `signing/xxkg-release.jks` 和 `keystore.properties` 备份到密码管理器/加密 U 盘/可信云盘。密码在 `keystore.properties` 里。
- 丢失 keystore 或密码后，未来版本无法覆盖安装，只能换新 key 再让用户卸载重装一次。

## Release 发布凭据

- `.github/workflows/release.yml` 使用仓库 Secret `GH_PAT` 创建 Release；**PAT 所有者就是 Release 作者**。
- PAT 需要能读写本仓库（经典 PAT 勾 `repo` 即可，建议 90 天过期）；只放在仓库 `Settings → Secrets and variables → Actions`，名称 `GH_PAT`，不要写进代码。
- 当前账号需要仓库 Secrets 管理权限；没有权限时由仓库管理员添加，或改用 PAT 手动创建 Release。
- PAT 过期/撤销后，自动发布失败；及时更新 Secret。

## 交付流程

1. 修改 `app/build.gradle.kts` 的 `versionCode` / `versionName`。
2. 在 `CHANGELOG.md` 和 `RELEASE_NOTES.md` 增加对应版本内容。
3. 编译并跑单元测试：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 :app:assembleDebug
   powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 :app:testDebugUnitTest
   ```
4. 打正式包并算摘要：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\release.ps1
   ```
   生成 `dist/xxtT-v2.2.1.apk` 和 SHA-256。
5. 打 Git tag：`v2.2.1`；GitHub Release 由 workflow 自动创建，标题和说明复用 `RELEASE_NOTES.md`。
6. 必须始终用同一把 keystore 签后续版本，否则无法覆盖安装；安装包文件名统一为 `xxtT-v版本号.apk`。

## 当前版本

- **2.2.1**（versionCode 20201）：应用正式更名为「学习通小工具 / xxt-Tool（xxtT）」；安装包统一命名为 `xxtT-v版本号.apk`。功能与 2.2.0 相同：页内 jsBridge、去掉原生详情页、倒计时颜色可配置、个人页精简；沿用 2.1.0 的正式签名。
- 2.2.0（20200）修正页内按钮无反应、精简交互；2.1.0（20100）加入应用内检查更新；2.0.0（20000）是签名迁移版本；1.1.0（10100）是切换签名前的 debug 预发布版。
- 下一版：2.2.2（PATCH，修 Bug）或 2.3.0（MINOR，新功能）。
