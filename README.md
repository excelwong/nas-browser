# NAS 浏览器 (NAS Browser) — 安卓客户端

> 当前版本 **v1.0.0** · 发布 APK：<https://github.com/excelwong/nas-browser/releases/download/v1.0.0/app-release.apk>

在安卓手机上浏览局域网 NAS 的 SMB 共享目录，多选文件/目录并下载到本机 **Download** 目录。
针对 **VIVO X Fold3 pro 折叠屏**（展开分辨率 2480×2200）做了网格自适应布局。

---

## 为什么用 SMB3 而不是 NFS

NAS 开启了 SMB1–3 与 NFS v2–v4.1，但**原厂安卓（含 VIVO 定制系统）没有内置 NFS 客户端**，
挂载 NFS 需要 root 权限，普通应用用不了。而 **SMB3 是未 root 手机上最快且最现代的可选协议**，
纯 Java 的 [`smbj`](https://github.com/hierynomus/smbj) 库原生支持、无需任何原生 `.so`。

应用会自动协商最高可用方言：**SMB 3.1.1 → 3.0.2 → 3.0 → 2.1 → 2.0.2**，
**绝不协商已废弃且不安全的 SMB1**。若 NAS 仅开 SMB2 也会自动回退。

---

## 功能

- **连接配置**：首次使用需手动填写 NAS 的主机 / 共享名 / 用户名 / 密码，连接成功后会**自动保存在本机**
  （EncryptedSharedPreferences，详见下方「增强功能」），之后打开自动回填；不预填任何个人信息。
- **目录浏览**：文件夹置顶、按名排序；支持返回上级 / 刷新。
- **多选下载（仅当前页面）**：在当前目录页勾选多个文件 / 整个目录即可下载，选目录会递归下载其下所有文件。**只下载当前页面所选内容**——切换目录后再点「下载选中」不会把之前在别的目录里选的东西一起下下来（选中状态按「共享 + 当前目录的直接子项」过滤）。
- **后台下载（前台服务）**：实际传输在一个独立的前台服务里执行，切到其他 App 再切回来不会中断；状态栏常驻通知显示整体进度，可随时点开「传输列表」查看 / 取消 / 继续。
- **本机存储**：下载到 `内部存储/Download/NasFiles/<选中目录名>/...`（只保留选中目录及其子目录，不含共享名与上级路径）。Android 10+ 走 MediaStore，**无需存储权限**。
- **折叠屏适配**：文件网格列数随屏宽自适应（展开 2480px 多列铺满），`values-w1240dp` 提供更大触控目标。

### 连接配置持久化（记住密码）

- 连接页提供「记住密码」勾选框（默认勾选）。
- 凭据保存在 **EncryptedSharedPreferences**（AndroidKeyStore 背书、AES 加密），密码不以明文落盘；
  设备不支持密钥库时回退到普通 SharedPreferences。
- 代码与文档中**不包含任何 NAS 地址 / 目录 / 账号 / 密码**等个人信息；首次打开连接页需手动填写，
  连接成功后保存在本机，下次自动回填。
- 浏览页右上角「设置」菜单可**编辑连接**（回填后修改重连）或**清除配置**（清空凭据，便于切换 NAS）。

### 并发下载 + 断点续传

- 下载引擎跑在一个**独立前台服务 `DownloadService`** 里：自带大小为 **4** 的线程池，
  最多同时下载 4 个文件；并维护一个同等大小（4）的 **SMB 连接池**（`SmbConnectionPool`），
  每个下载线程独占一条已认证的 SMB 会话，互不争用，真正并行拉取。
- 下载先写入应用私有目录临时文件 `.../Download/.nasparts/<相对路径>.part`（真实可定位，支持任意字节偏移续写）。
  若检测到 `.part` 已存在，则从已有长度处 `SMB read(offset)` 继续，**不重头下载**。
- 传输列表以 JSON 持久化。进程被杀后重连，点「继续 / 重试」即可从未完成处续传；
  传输中可「取消」（保留 `.part`，状态变「已暂停」，可继续）。
- 文件完整下载后，才从临时文件移动到公共 Download 目录（MediaStore）。

### 为什么切后台不再「线程乱套」

- 之前下载线程与「浏览」共用同一个 SMB 连接池，切到别的 App 再切回来时 UI 重新列目录会 `reconnect()`
  把下载线程正在用的连接关掉，于是多个下载线程互相踩踏。
- 现在下载连接池与浏览连接池**完全分离**，浏览重连绝不会波及后台下载；下载在切 App、甚至进程被系统
  回收后重连都能继续，状态栏常驻通知显示整体进度。
- 重连走**协调式重建**：连接池用 `AtomicReference` 持有，重建经 `synchronized` + 双重检查 ——
  同一时刻**只有一个线程**真正重建池，其余检测到连接过期的线程直接复用这次结果并重试，
  不再各自 `reconnect()` 造成雪崩。

### 应用内打开 NAS 上的图片 / 视频

- 图片、视频文件行尾出现「播放 / 打开」按钮。
- 点击后先下载到本机应用私有缓存（`.nasopen`），再用系统默认的图片 / 视频应用打开播放，
  **不依赖任何第三方播放器**（若本机没有对应查看器会提示「未找到可播放该文件的应用」）。

---

## 下载已发布版本

已签名的发布 APK 可在 GitHub Release 直接获取（无需自行构建）：

- **v1.0.0**（最新稳定版）：<https://github.com/excelwong/nas-browser/releases/download/v1.0.0/app-release.apk>
- 发布页：<https://github.com/excelwong/nas-browser/releases/tag/v1.0.0>

---

## 如何构建

> **Gradle 版本要求**：本工程的 Android Gradle Plugin 需要 **Gradle ≥ 8.7**。
> 注意仓库里的 `gradle-wrapper.properties` 与 `.toolchain/install_build.sh` 当前仍写的是 **Gradle 8.6**，
> 直接用它编译会报 “AGP requires Gradle ≥ 8.7” 而失败。请用下面「方式 B」里的本地 Gradle 8.9，
> 或先把脚本 / wrapper 里的版本号改为 8.9。

### 方式 A：Android Studio（推荐，约 2 分钟）
1. `File → Open` 选择本目录 `nas-browser`。
2. 等待 Gradle 同步完成（若 wrapper 是 8.6，请在 `gradle-wrapper.properties` 改为 8.9 再同步）。
3. 手机开启「USB 调试」后用 ▶ Run 安装到 VIVO X Fold3 pro。

### 方式 B：命令行（仓库内已附带离线工具链）
本仓库根目录的 `toolchain/` 已内置 **JDK 17 + Gradle 8.9 + Android SDK**（`platform-35` /
`build-tools;34.0.0` / `platform-tools`），无需联网即可构建。离线构建示例：

```bash
# 用本地 Gradle 8.9（不要用 wrapper 的 8.6）
export JAVA_HOME="$PWD/toolchain/jdk/jdk-17.0.20+8"
export GRADLE_USER_HOME="$PWD/../.gradle"   # 或你的 Gradle 缓存目录
export PATH="$JAVA_HOME/bin:$PATH"
"$PWD/toolchain/gradle-8.9/bin/gradle.bat" --offline assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

### 构建正式发布版（签名 APK）

发布版会走 `signingConfigs.release`：读取仓库根目录的 **`release-signing.properties`**
（含 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`，**已被 `.gitignore` 排除，不入库**）。
首次发布前需先生成自己的 keystore：

```bash
keytool -genkeypair -v -keystore release.keystore -alias nasbrowser \
  -keyalg RSA -keysize 2048 -validity 10000
# 然后新建 release-signing.properties 填入上面的四项；之后：
"$PWD/toolchain/gradle-8.9/bin/gradle.bat" --offline assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk（已签名，V1/V2/V3）
```

> 升级 App 必须使用同一个 `release.keystore`，请妥善备份 `release.keystore` 与 `release-signing.properties`。

---

## 安装到手机

```bash
adb install -r app/build/outputs/apk/release/app-release.apk   # 正式版
# 或：adb install -r app/build/outputs/apk/debug/app-debug.apk  # 调试版
```
VIVO 需先开启：设置 → 系统管理 → 关于手机 → 连续点击「软件版本号」开启开发者选项 →
开启「USB 调试」；首次侧载若提示「禁止安装未知应用」，按提示允许本次安装。

---

## 权限说明
- `INTERNET` / `ACCESS_NETWORK_STATE`：访问局域网 NAS。
- `WRITE/READ_EXTERNAL_STORAGE`：仅 `maxSdkVersion=28/32` 时才需要（旧系统直写文件）；
  Android 10+ 通过 MediaStore 下载，**不申请存储权限**。

## 已验证的关键 API 点（smbj 0.13.0）
- `DiskShare.openFile` 的 attributes 参数类型为 **`com.hierynomus.msfscc.FileAttributes`**
  （不是 `msdtyp.FileAttributes`，后者会导致编译失败）。
- 读取：`SmbFile.read(byte[] buffer, long fileOffset)` 返回实际字节数，`-1` 表示 EOF。
- 取大小：`share.getFileInformation(path, FileStandardInformation::class.java).endOfFile`。
