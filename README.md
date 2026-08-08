# NAS 浏览器 (NAS Browser) — 安卓客户端

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

### 本次新增的增强

1. **连接配置持久化（记住密码）**
   - 连接页新增「记住密码」勾选框（默认勾选）。
   - 凭据保存在 **EncryptedSharedPreferences**（AndroidKeyStore 背书、AES 加密），
     密码不以明文落盘；设备不支持密钥库时回退到普通 SharedPreferences。
   - 下次打开自动回填主机 / 共享名 / 用户名 / 密码。

2. **下载并发加速（多线程拉多个文件）**
   - 内置大小为 **3** 的线程池，最多同时下载 3 个文件。
   - 维护一个同等大小的 **SMB 连接池**（`SmbConnectionPool`），每个下载线程独占一条
     已认证的 SMB 会话，互不争用，真正并行拉取。

3. **大文件断点续传**
   - 下载先写入应用私有目录下的临时文件 `.../Download/.nasparts/<相对路径>.part`
     （真实可定位文件，支持任意字节偏移的续写）。
   - 开始下载前若检测到 `.part` 已存在，则从已有长度处 `SMB read(offset)` 继续，
     **不重头下载**。
   - 传输列表以 JSON 持久化；App 被杀后重连，点「继续/重试」即可从未完成处续传。
   - 传输中可「取消」（保留 `.part`，状态变为「已暂停」，可继续）。
   - 文件完整下载后，才从临时文件移动到公共 Download 目录（MediaStore）。

### 本次修复与架构调整（后台下载 + 不再线程乱套）

1. **下载搬进前台服务（`DownloadService`）**
   - 下载引擎（SMB 连接池 + 下载线程池 + 续传逻辑）从 `BrowserViewModel` 整体移到了一个独立的前台服务里。
   - 下载连接池与「浏览」连接池**完全分离**：之前二者共用同一个池，切后台后再回来时 UI 重新列目录会 `reconnect()` 把下载线程正在用的连接关掉，于是「多个线程乱套」。现在浏览重连只影响浏览，绝不会波及后台下载。

2. **重连不再踩踏（协调式重建）**
   - 连接池引用用 `AtomicReference` 持有，重建走 `synchronized` + 双重检查：同一时刻**只有一个线程**会真正重建池；其余检测到连接过期的线程直接复用这次重建结果并重试，不再各自去 `reconnect()` 造成雪崩 / 把别人手里的连接关掉。

3. **真正的后台 + 断点续传**
   - 前台服务保证切 App、甚至进程被系统回收后重连，传输都能继续；传输列表以 JSON 持久化，重连后「继续 / 重试」从未完成处续传。
   - 「下载选中」只认 `visibleSelection`（当前共享 + 当前目录的直接子项），跨目录选择不会被带入批次。

### 本次新增的功能（第二版）

1. **连接信息不再预填，首次使用手动填写**
   - 代码与文档中**不再包含任何 NAS 地址 / 目录 / 账号 / 密码**等个人信息。
   - 首次打开连接页，主机 / 共享名 / 用户名 / 密码均为空，需用户自行填写；连接成功后保存在本机。

2. **可在 App 内修改 / 清除连接配置**
   - 浏览页右上角「设置」菜单提供：
     - **编辑连接**：返回连接页（自动回填已保存的信息），可修改后重新连接。
     - **清除配置**：清空本机保存的凭据，返回空白连接页，便于切换到另一台 NAS。

3. **直接播放 / 打开 NAS 上的图片与视频**
   - 图片、视频文件行尾出现「播放 / 打开」按钮。
   - 点击后会先下载到本机应用私有缓存（`.nasopen`），再通过系统默认的图片 / 视频应用打开播放，
     **不依赖任何第三方播放器**（若本机没有对应查看器会提示「未找到可播放该文件的应用」）。

---

## 如何构建

### 方式 A：Android Studio（推荐，约 2 分钟）
1. `File → Open` 选择本目录 `nas-browser`。
2. 等待 Gradle 同步完成。
3. 手机开启「USB 调试」后用 ▶ Run 安装到 VIVO X Fold3 pro。

### 方式 B：命令行（仓库内已附带离线构建脚本）
仓库内的 `.toolchain/install_build.sh` 会自动下载 JDK17 + Gradle 8.6 + Android SDK
（`platform-35` / `build-tools;34.0.0` / `platform-tools`），接受许可，并产出 debug APK：
```bash
bash nas-browser/.toolchain/install_build.sh
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

---

## 安装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
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
