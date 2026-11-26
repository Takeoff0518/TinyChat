# 发版手册（M9）

> 这份文档只讲「怎么把一个正式版发出去」，包含本机出包、CI 出包、以及每次出包前必须做的产物体检。
> 面向的是维护者本人，步骤按顺序照做即可；每一步失败都写了原因，不用猜。

## 1. 只有一次性的准备

### 1.1 签名密钥

正式版的签名密钥放在 `.local/keystore/`，这个目录被 `.gitignore` 挡住，**永远不会进仓库**：

```
.local/keystore/tinychat-release.jks      密钥本体（RSA 4096，有效期 30 年）
.local/keystore.properties                口令与别名
```

`app/build.gradle.kts` 只有在读到 `keystore.properties` 时才注册 release 签名；读不到就让 release 处于未签名状态。这样做是为了让别人的 clone 和临时环境不会因为缺密钥而构建失败。

生成一份新的（只有第一次需要）：

```powershell
keytool -genkeypair -v -keystore .local\keystore\tinychat-release.jks -storetype PKCS12 `
  -alias tinychat -keyalg RSA -keysize 4096 -validity 10950 `
  -storepass <口令> -keypass <口令> `
  -dname "CN=TinyChat, OU=Android, O=TinyChat, L=Unknown, ST=Unknown, C=CN"
```

> ⚠️ **密钥和口令必须另存一份**（密码管理器或离线备份）。丢失之后，已经发出去的版本就再也做不出升级包了——用户只能卸载重装，数据一起没。

### 1.2 CI 需要的四个 Secrets

`.github/workflows/release.yml` 在打 tag 时运行，从仓库 Secrets 里还原同一份密钥：

| Secret 名 | 内容 |
|---|---|
| `KEYSTORE_BASE64` | 密钥文件的 base64（见下） |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `tinychat` |
| `KEY_PASSWORD` | 私钥口令（PKCS12 下与 keystore 口令相同） |

生成 base64（Windows）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".local\keystore\tinychat-release.jks")) | Set-Clipboard
```

## 2. 发一版

### 2.1 改版本号

`app/build.gradle.kts` 里两行：

```kotlin
versionCode = 1        // 每发一版 +1，只增不减
versionName = "1.0.0"  // 用户看到的版本
```

`versionCode` 减了或者不变，系统就不让覆盖安装，用户必须卸载——所以要改，而且只能往上改。

另外两处跟着版本走，别忘：

- **tag 名与 `versionName` 保持一致**，写成 `v1.0.0` 这种形式。README 顶部的 Release 徽章（`img.shields.io/github/v/release/...`）直接读最新 tag，tag 打对了它自己就会变，不用手改文档。
- **README 里「安装包体积仅约 4 MB」这句要核对**：体积随依赖与 R8 规则变，发版时看一眼 `app-release.apk` 的实际大小，差得多就改掉。

### 2.2 全量测试与本机出包

```powershell
.\gradlew.bat test assembleDebug assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

### 2.3 确认签名

```powershell
& "$env:ANDROID_SDK_ROOT\build-tools\37.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
```

应当看到 `CN=TinyChat`，以及 `Verified using v2 scheme: true`。

### 2.4 产物体检（**每次都要做**）

发布包里不该出现的东西：开发用的 API Key、签名口令、调试样本、测试框架的类。

```powershell
# 解包
$dst = ".local\apkcheck"; Remove-Item -Recurse -Force $dst -ErrorAction SilentlyContinue
New-Item -ItemType Directory $dst | Out-Null
Copy-Item app\build\outputs\apk\release\app-release.apk "$dst\app.zip"
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory("$dst\app.zip", "$dst\x")

# 逐个文件搜敏感字面量
$files = Get-ChildItem "$dst\x" -Recurse -File
foreach ($f in $files) {
  $t = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($f.FullName))
  if ($t.Contains("<开发 Key 的字面量>")) { "HIT: $($f.Name)" }
}

# 数 dex 里的类描述符：都应当是 0
$dex = (Get-ChildItem "$dst\x" -Filter *.dex | ForEach-Object {
  [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($_.FullName)) }) -join "`n"
"Lorg/robolectric/","Lokhttp3/mockwebserver/","Landroidx/test/","Ljunit/","Landroidx/compose/ui/tooling/" |
  ForEach-Object { "$_ => " + ([regex]::Matches($dex, [regex]::Escape($_))).Count }
```

**看到的两个例外不算问题**：字符串池里可能出现 `robolectric`、`okhttp3.mockwebserver.MockWebServer` 这两个**字符串**，那是 OkHttp 在运行时探测自己跑在什么平台上用的常量，不是类——类描述符计数为 0 就说明类没打进去。

### 2.5 打 tag 发布

```powershell
git tag v1.0.0
git push origin v1.0.0
```

CI 会出包并把 APK 挂到 GitHub Releases。也可以不发 CI，直接手动在 Releases 页面传 `app-release.apk`。

### 2.6 首次发布踩到的三个坑（2026-11-22，v1.0.0）

1. `android-actions/setup-android@v3` 默认会装 `tools` 包，而新版 SDK 仓库里已经没有它，CI 直接挂在 **Set up Android SDK**。两个工作流现在都显式写 `packages: platform-tools`。
2. Windows 上提交的 `gradlew` 丢了可执行位，Linux runner 上就是 `./gradlew: Permission denied`。已用 `git update-index --chmod=+x gradlew` 修好；以后换机器首次提交要留意这个位。
3. release 里原来写 `chmod +x "$apksigner"`，runner 上的 SDK 目录是只读的，会 `Operation not permitted`。改成 `bash "$apksigner"` 即可，脚本不需要可执行位。

四个 Secrets 已经设进仓库（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），密钥只存在 Secrets 里。**tag 打错时**可以先 `git push -f origin <tag>` 把 tag 挪到修好的提交——只要 Release 还没发布出去，这样做是安全的。

### 2.7 发版说明里必须写的一句

> 正式包的签名与之前测试版不同，安装前需要先卸载旧版本，**本地会话与 API Key 会一起清除**，请先导出数据。

### 2.8 第二次发布（2026-11-25，v1.1.0）

- `versionCode 2` / `versionName 1.1.0`。从 1.0.0 覆盖安装、数据库 schema 5→6 迁移都在真机上验过。
- 第一次走「先合并、再打 tag」：`integration/1.1.0` 合进 `master`（merge commit 保留原提交），CI 绿后打 `v1.1.0`，Release 工作流 4 分 8 秒出包，产物 `TinyChat-v1.1.0.apk`。
- 冒烟在**合并前**完成：安装升级、数据迁移、冷启动 912ms、文字回答 62–68 帧 / 卡顿 4.88%、代码块 55–60 帧 / 14.4%、七种语言、相册排序与授权、PDF 与 DOCX 提取、搜索三供应商、停止生成与继续生成、正文引用角标。
- 这一版主要是收下一个外部 PR（163 文件）并删掉其中两个假功能，过程见 `docs/09-merge-1.0.8.md` 与决策 75–79。

### 2.9 第三次发布（2026-11-26，v1.1.1）

- `versionCode 3` / `versionName 1.1.1`，直接在本机出包后打 tag（没有分支，六件事都在 `master` 上）：思考内容自动折叠与折叠头改由协议驱动（决策 80）、划入新回答的瞬时跳跃（P-046/决策 82）、正文按块拆成列表项（B-015/决策 83）、空回答不占版本位（P-048/决策 84）、拒答按结果展示（P-049/决策 85）、每轮用量折成一行并点开给明细（P-050/决策 86）。
- **本版追加过一个 bug**：明细卡的标签在 `Popup` 里解析不到应用语言（中文界面弹出英文标签），修复与验证见决策 87；tag 打在修复之后。
- 冒烟只做了**与改动相关的项**，没有重跑 `08` 的十六项动作序列（1.1.0 刚跑过，本轮不碰相册/语音/搜索/分享这些链路）：
  - 覆盖安装 `versionCode 2 → 3`：Success，抽屉里 12 条会话全在、Key 未重填；
  - 关于页显示「1.1.1」；冷启动 `am start -W` 781ms / 453ms（1.1.0 记的是 912ms）；
  - 每轮用量行：折叠行与明细卡在简体、繁體下各验一次，有费用（`￥0.017`）与无费用（`99 tok`）两种会话都验（P-050、P-051）；
  - 签名 `CN=TinyChat`、V2、证书指纹仍为 `9eccb46d…fd89`；产物 4,216,288 B（README 的「约 4 MB」仍成立，未改）；
  - 产物体检按 2.4：开发 Key 零命中，`Lorg/robolectric/`、`Lokhttp3/mockwebserver/`、`Landroidx/test/`、`Ljunit/`、`Landroidx/compose/ui/tooling/` 全为 0。
- APK SHA-256：`D63356D461C71491C3F626095DC0C0CADFF199BECDC8632D8BEED8F36A71C2AC`（**本机产物**）。
- CI 出包：`Release` 工作流 6 分 10 秒，产物 `TinyChat-v1.1.1.apk`，体积与本机一致（4,216,288 B）、证书一致、`versionCode 3 / versionName 1.1.1` 核对无误；但**字节不同**（zip 时间戳与 R8 顺序跨机器不一致），要校验就比体积、证书与版本号，**不要比 SHA-256**。
- 这一版的 Release 说明是手写的（`gh release edit --notes-file`），没有用 `--generate-notes` 的「只有 Full Changelog」那版；以后发版沿用「六条改了什么 + 修好了什么 + 安装方式」三段。
- 第一次推上去时 `CI`（master）挂在 **8 秒**：`org.jetbrains.kotlin.jvm:2.4.21` 在 Google / MavenRepo / Gradle Central Plugin Repository 全都解析不到。同一时刻的 `Release` 跑同样的 Gradle 命令却是绿的，`gh run rerun` 之后也转绿——属于**插件仓库的偶发解析失败**，不是代码问题。这类外网故障现在已有缓存与重试兜底，见 §2.10。

### 2.10 CI 的两处兜底：依赖缓存与 PR 触发（2026-11-26）

发 1.1.1 那天 `master` 的 CI 连红两次，都不是代码的问题：

1. **8 秒就挂**：`org.jetbrains.kotlin.jvm:2.4.21` 在 Google、MavenRepo、Gradle Central Plugin Repository 全都解析不到；同一时刻的 Release 跑同样的 Gradle 命令是绿的。
2. **3 分钟挂**：六个 Robolectric 用例整组 `classMethod FAILED`，堆栈是 `MavenArtifactFetcher` 里的 `HttpURLConnection` 抛 `IOException`——它**不走 Gradle 缓存**，每台新 runner 都要从 Maven Central 现下 `android-all` jar（实测一次 374 MB）。

共同点是「依赖得从外网现拉」。现在三条兜底：

- **Robolectric 依赖进缓存**：`actions/cache` 存 `~/.m2/repository/org/robolectric`，key 是 `robolectric-<os>-<libs.versions.toml 的哈希>`，升 Robolectric 版本自动换一份。第一次跑把它存进去，之后每次 `Cache hit` 直接命中（实测带缓存的一次 CI 65 秒，之前 2 分 45 秒）。
- **PR 也跑**：`pull_request` 只对 `master`/`main` 生效，事件是「打开 / 追加提交 / 重开 / 标为可评审」；丢掉的是原来那句「任何 PR 任何事件都跑」的模糊配置。验证过程记在这里：开了一个空提交的临时 PR（#4），`gh run list` 看到 `event=pull_request`、日志里 `Cache restored from key`，验完关闭并删分支。
- **失败重试一次**：Gradle 命令挂了再跑一遍，红线留给真错误；网络抖一下就过的，不用人再点一次 rerun。

以后再遇到「几秒挂、报某个依赖在所有仓库都找不到」，先看是不是又冒出一个没被缓存的下载器，别急着怀疑代码。

## 3. 记忆点

- 密钥在 `.local/keystore/`，repo 里没有；`benchmark` 与 `debug` 用的是 debug 签名，**不是**发布签名。
- `versionCode` 只增不减。
- 每次出包跑一遍 2.3 与 2.4。
- `app/build/outputs/mapping/release/mapping.txt` 是崩溃栈反混淆用的，发版时应当一起归档（体积有几十 MB，不进仓库、也不进 APK）。
