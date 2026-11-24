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
  -dname "CN=<发布证书名称>"
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

将输出的证书指纹与本地保留的发布证书记录比对，并确认 `Verified using v2 scheme: true`。

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

## 3. 记忆点

- 密钥在 `.local/keystore/`，repo 里没有；`benchmark` 与 `debug` 用的是 debug 签名，**不是**发布签名。
- `versionCode` 只增不减。
- 每次出包跑一遍 2.3 与 2.4。
- `app/build/outputs/mapping/release/mapping.txt` 是崩溃栈反混淆用的，发版时应当一起归档（体积有几十 MB，不进仓库、也不进 APK）。
