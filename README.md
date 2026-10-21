# DSH 控制台

用手机监控和控制你的开发服务器 —— 状态面板、Web 终端、文件管理、编译日志、任务与消息。

<p align="center">
  <img src="docs/icon.png" width="96" alt="DSH 控制台图标">
</p>

---

## 这是什么

一个 Android 客户端 + 一个轻量 Node 后端，让你在手机上：

- **看**服务器状态：CPU / 内存 / 磁盘 / 温度 / 守护进程，一键诊断
- **敲**命令：内嵌 Web 终端，带 Termux 风格的辅助键盘
- **传**文件：浏览、上传、下载、删除
- **盯**编译：实时编译日志推送，不用守着电脑
- **收**消息：服务端推送通知，支持回复

**不需要 root，不需要在手机上装任何东西。**

## 特性

| | |
|---|---|
| 状态面板 | CPU/内存/磁盘/网速实时曲线，守护进程网格 |
| 一键诊断 | 一键跑完全部健康检查，生成可复制的报告 |
| Web 终端 | 内嵌 ttyd，Termux 风格辅助键盘（ESC/方向键/Ctrl/Alt） |
| 文件管理 | 浏览、上传、下载、删除，路径白名单保护 |
| 实时日志 | SSE 推送，编译输出逐行滚动 |
| 任务与消息 | 服务端上报任务进度、推送消息，未读角标 |
| Material 3 | 完整 M3 组件，支持莫奈动态取色 |
| 快捷命令 | 预置命令一键执行，危险命令二次确认 |

## 快速开始

### 1. 部署后端

```bash
git clone <this-repo>
cd dsh-console/server
sudo ./install.sh /opt/dsh-console
```

编辑生成的 systemd 单元，填好文件白名单和日志路径：

```bash
sudo nano /etc/systemd/system/dsh-console.service
```

关键配置项：

| 变量 | 说明 | 默认 |
|---|---|---|
| `DSH_CONSOLE_PORT` | 监听端口 | `8080` |
| `DSH_CONSOLE_HOST` | 监听地址 | `0.0.0.0` |
| `DSH_CONSOLE_ROOTS` | 文件白名单 `名称:路径[:rw\|ro]`，逗号分隔 | `Home:$HOME:rw,Tmp:/tmp:rw` |
| `DSH_CONSOLE_LOG` | 编译日志文件路径 | `./build.log` |
| `DSH_CONSOLE_STATE_DIR` | 任务/消息持久化目录 | `./state` |
| `DSH_CONSOLE_DAEMONS` | 守护进程 `名称:单元名[:critical\|optional]` | 空 |
| `DSH_CONSOLE_DISK_PATH` | 磁盘使用率统计的挂载点 | `/` |
| `DSH_CONSOLE_MAX_UPLOAD` | 上传大小上限（字节） | `2147483648`（2 GiB） |
| `DSH_CONSOLE_CMDS_FILE` | 自定义快捷命令 JSON 文件 | 内置只读命令集 |
| `DSH_CONSOLE_TOKEN` | API 令牌（可选） | 空 |

改完重启：

```bash
sudo systemctl restart dsh-console
```

### 2. 装 App

从 [Releases](https://github.com/<owner>/<repo>/releases) 下载 APK 安装。

### 3. 填服务器地址

打开 App → **设置 → 服务器**：

```
服务器地址    192.168.1.10     ← 你的服务器 IP 或域名
状态端口      8080
DSH 地址      http://192.168.1.10:19387/
终端地址      http://192.168.1.10:7681/
文件地址      http://192.168.1.10:8080
```

没填之前，App 会提示「请在设置中提供服务器地址」。

## 可选组件

### Web 终端

终端功能需要 [ttyd](https://github.com/tsl0922/ttyd)：

```bash
sudo apt install ttyd
sudo systemctl enable --now ttyd@8080
```

`ttyd` 建议启用 Basic Auth（App 会带上你填的终端用户名/密码）。

### 编译日志接入

用 `build-log` 包装器把构建输出写进日志文件：

```bash
# 把任意构建命令的输出实时写入日志
build-log build gradle :app:assembleRelease
build-log recovery mka recoveryimage
```

包装器用 `stdbuf -oL tee -a` 做行缓冲，所以日志会一行一行地推送到手机。

也可以自己写：

```bash
stdbuf -oL your-build-command 2>&1 | tee -a /path/to/build.log
```

## 项目结构

```
dsh-console/
├── app/                      # Android 客户端
│   └── src/main/
│       ├── java/io/github/dshconsole/app/
│       │   ├── MainActivity.java      # 底栏、页面路由
│       │   ├── HomeFragment.java      # 状态面板
│       │   ├── TerminalFragment.java  # Web 终端 + 辅助键盘
│       │   ├── FilesFragment.java     # 文件管理
│       │   ├── LogsFragment.java      # 编译日志（WebView）
│       │   ├── MessagesFragment.java  # 消息中心
│       │   ├── CommandsFragment.java  # 快捷命令
│       │   ├── DshFragment.java       # DSH 状态
│       │   ├── SettingsFragment.java  # 设置
│       │   ├── AppSettings.java       # 配置存储
│       │   └── ...
│       └── res/
├── server/                   # Node 后端
│   ├── server.js             # 统一服务（状态/任务/消息/文件/日志）
│   ├── package.json          # npm start / npm test
│   ├── install.sh            # 安装脚本
│   ├── systemd/              # systemd 单元
│   └── test/
│       └── contract.test.js  # 接口契约测试（锁住 App 依赖的字段名）
└── docs/
```

## API

所有接口都在一个端口上。启用 `DSH_CONSOLE_TOKEN` 后需带 `X-Auth-Token` 头（或 `?token=`）。

### 核心接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/progress` | **App 首页/后台监控消费的总览**：`{ts, tasks, system, alerts, messages, daemons}` |
| GET | `/api/status` | 扁平状态 JSON（脚本用，字段同 `progress.system`） |
| GET | `/health` | 健康检查 |
| GET | `/daemons` | 守护进程 |
| GET | `/diagnose` | 一键诊断 |
| GET | `/tasks` | 任务列表 |
| POST | `/task` | 上报任务 `{id,name,progress,state,detail,eta}` |
| POST | `/task/done` | 结束任务（`{"all":true}` 全部清空） |
| GET | `/messages` | 消息列表（最近 50 条，倒序） |
| POST | `/message` | 发送消息 `{title,body,from}` |
| POST | `/message/read?id=` | 标记单条已读 |
| POST | `/message/read-all` | 全部已读 |
| GET | `/cmds` | 快捷命令列表 |
| POST | `/cmd?id=` | 执行快捷命令，返回 `execId` |
| GET | `/cmd/result?id=` | 轮询命令输出 `{running,code,output}` |
| GET | `/roots` | 文件根目录 |
| GET | `/list?root=0&path=/` | 列目录（`items[]`、`absPath`、`count`、`writable`） |
| GET | `/info?root=0&path=/x` | 文件详情 |
| GET | `/download?root=0&path=/x` | 下载（支持 `Range` 断点续传） |
| POST | `/upload?root=0&path=/&name=f` | 上传（body 为原始字节） |
| POST | `/delete?root=0&path=/x&confirm=YES` | 删除（必须显式确认） |
| GET | `/api/logs` | 编译日志：**浏览器/WebView 打开是页面**，`Accept: application/json` 或 `?format=json` 时返回 JSON |
| GET | `/api/logs/stream` | 日志 SSE 实时流 |

> `progress` / `list` / `cmds` 等路径前面的 `/api` 可以省略（如 `/api/status` ≡ `/status`），
> 两套路径都保留，方便旧脚本平滑迁移。

### 关于 `/progress` 的字段契约

App 端的 `Snapshot.java` 会**逐个字段**读取 `system` 对象，字段名和类型是硬约定：

```
cpu(int %) cpuCores cpuModel load1 load5 cpuTemp(int)
mem(int %) memTotal memUsed memFree swapTotal swapFree
disk(int %) diskFree diskTotal tailscale(bool) uptime host
netRx netTx netRxSpeed netTxSpeed netIface
diskRead diskWrite diskReadSpeed diskWriteSpeed diskIface
```

改后端时不要随手重命名这些字段，否则首页会显示全 0。

## 安全说明

- **文件访问**受根目录白名单约束，路径穿越被拦截
- **删除**需要 `confirm=YES` 显式确认
- **API 令牌**可选，启用后所有接口都要带令牌
- **明文 HTTP** 是允许的（自建服务器常见），请自行用防火墙限制访问来源
- App **不预置任何默认服务器**，必须由使用者配置

建议：后端只监听局域网，或在防火墙上只放行你手机所在的网段。

## 测试

后端自带一份自包含的接口契约测试（会自己起临时实例、造临时目录，测完自动清理）：

```bash
cd server
npm test          # 或 node test/contract.test.js
```

它会断言 App 真正依赖的那些字段名和类型 —— 比如 `/progress` 必须有 `system` 对象、
`/list` 必须返回 `items` 而不是 `entries`、`root` 必须是字符串、删除必须二次确认、
`../../` 和符号链接逃逸必须被拦。**这些地方一旦改崩，App 不会报错，只会安静地显示
全 0 / 空目录 / 只读**，所以必须有断言看着。

## 构建

前置：**JDK 17+**、**Android SDK 36**（用 `ANDROID_HOME` 或仓库根目录的 `local.properties` 指向 SDK）。

```bash
# 仓库自带 Gradle Wrapper，不需要本机装 gradle
./gradlew assembleRelease      # 产物: app/build/outputs/apk/release/app-release.apk
./gradlew installDebug         # 直接装到已连接的设备
```

> Wrapper 默认从腾讯云镜像拉 Gradle 8.14（`services.gradle.org` 会 307 跳到
> GitHub Releases，国内经常拉不动）。海外网络可以改
> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 回官方地址。

**签名**：release 构建默认不签名（开源版由使用者自行签名）。需要签名时在
`~/.gradle/gradle.properties` 里配置：

```properties
DSHCONSOLE_STORE_FILE=/绝对路径/release.keystore
DSHCONSOLE_STORE_PASSWORD=***
DSHCONSOLE_KEY_ALIAS=dshconsole
DSHCONSOLE_KEY_PASSWORD=***
```

密钥库不要提交到仓库（`.gitignore` 已经排除了 `*.keystore`）。
没配置签名时会产出一个未签名的 release APK，可以直接用于分发测试。

## License

MIT —— 见 [LICENSE](LICENSE)。

## 致谢

- [ttyd](https://github.com/tsl0922/ttyd) —— Web 终端
- [Material Components for Android](https://github.com/material-components/material-components-android) —— UI 组件
- [Noto Sans CJK](https://github.com/notofonts/noto-cjk) —— 中文字体（SIL OFL，见 [docs/FONT-LICENSE.txt](docs/FONT-LICENSE.txt)）
