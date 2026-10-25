# E33ChatPaper

E33Chat 的 **Paper 服务端插件**（MVP：只做「引用跨玩家同步」）。装了它的 Paper 服务器可以和已有的
Fabric 客户端互通，**客户端一行都不用改**。

```
Fabric 客户端 --C2S e33chat:quote_sync--> E33ChatPaper --S2C e33chat:chat_meta--> 所有在线玩家（含发送者）
```

服务端只做三件事：解码 4 个字符串 → 按 `<uuid>|<msgHash>` 做 10 秒去重 → 编码 7 个字段后广播。
不碰世界数据/实体/命令树，所以同一套线格式可以直接搬到 Bukkit 的插件通道 API 上。

## 线格式（与 Fabric 侧逐字节一致）

`string` = `VarInt(UTF-8 字节数)` + UTF-8 字节（= Minecraft `StringEncoding.encode/decode`，
默认上限 32767 字符 / 98301 字节）。`list` = `VarInt(元素数)` + 逐元素编码。

| # | `e33chat:quote_sync`（C2S） | 类型 | 编码 |
|---|---|---|---|
| 1 | quotedSenderName | string | VarInt 字节数 + UTF-8 |
| 2 | quotedContent | string | 同上 |
| 3 | messageHash | string | 同上（本次消息明文的 `String.hashCode()` 十进制串） |
| 4 | quotedHash | string | 同上（被引用消息的 hashCode；旧客户端不发 → 读成 `""`） |

| # | `e33chat:chat_meta`（S2C） | 类型 | 编码 |
|---|---|---|---|
| 1 | senderUUID | string | `UUID.toString()`，36 个 ASCII 字符 |
| 2 | senderName | string | VarInt 字节数 + UTF-8 |
| 3 | messageHash | string | 同上 |
| 4 | quoteSender | string | 同上 |
| 5 | quoteContent | string | 同上 |
| 6 | mentionTargets | list\<string\> | `VarInt(元素数)` + 逐元素 string（MVP 恒 `0`） |
| 7 | quotedHash | string | 同上 |

字段顺序/类型不是照着文档抄的：`verify/` 下的离线校验程序拿 **yarn 1.21.11 的原版 `PacketByteBuf`**
当基准，双向比对（原版编码 → 本插件解码；本插件编码 → 与原版编码整条 `byte[]` 全等），34 项全过。

## 构建

本机固定用工作区里的 Gradle 发行版与共享缓存：

```powershell
$env:GRADLE_USER_HOME = 'C:\AI\ds harness\工作区\聊天mod移植1.21.11\.gradle-home'
$env:JAVA_TOOL_OPTIONS = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7899 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7899'
& 'C:\AI\ds harness\工作区\聊天mod移植1.21.11\gradle-dist\gradle-9.5.1\bin\gradle.bat' --no-daemon --console=plain build
```

产物：`build/libs/E33ChatPaper-3.0.1.jar`（约 11 KB，`paper-api` 是 `compileOnly`，不打进 jar）。

> 判据只看输出里的 `BUILD SUCCESSFUL`。PowerShell 会把 JVM 的 `Picked up JAVA_TOOL_OPTIONS`
> 当错误，退出码不可信；偶发毫秒级失败（daemon/锁竞争）隔 4 秒重试即可（最多 3 次）。

## 安装与测试

1. 把 `build/libs/E33ChatPaper-3.0.1.jar` 放进 Paper 1.21.11 服务器的 `plugins/`，重启服务器。
2. 服务器日志应出现：
   ```
   [E33Chat] E33ChatPaper enabled | in=e33chat:quote_sync | out=e33chat:chat_meta | dedupeWindow=10000ms
   ```
3. 客户端白名单（决定「客户端会不会向该服声明自定义通道」）：
   - **局域网 / 私有网段地址**（127.x / 10.x / 192.168.x / 172.16-31.x）：配置阶段直接放行，**首次连接**
     就能用，不需要改任何文件。
   - **公网地址**：`<实例目录>/config/e33chat/e33chat-servers.txt`，一行一个地址（小写，与多人游戏
     列表里填的完全一致，非默认端口要带 `:端口`）。也可以在首次进入后让客户端自动记忆
     （JOIN 时 `canSend(e33chat:quote_sync)` 为真 → 写进该文件，**从下一次连接起**生效）。
4. 验证：玩家 A 右键引用一条消息并发送，服务端日志出现
   ```
   [E33Chat] quote_sync recv | player=A | msgHash=... | quotedHash=... | quoteSender=... | quote=...
   [E33Chat] chat_meta broadcast | msgHash=... | recipients=2
   ```
   玩家 B（以及 A 自己）的气泡里应出现引用块。

## 排错

| 现象 | 原因 / 处理 |
|---|---|
| 日志没有 `E33ChatPaper enabled` | jar 没被加载：确认是 `paper-plugin.yml` 在 jar 根目录、`main` 类存在；看服务器启动日志里的插件加载错误 |
| 有 `quote_sync recv` 但没有 `chat_meta broadcast` | 命中去重（10 秒内同 `msgHash`）——日志里会是 `chat_meta skipped (dup)` |
| `quote_sync dropped \| ... IllegalArgumentException` | 客户端与本插件的协议不一致或包被截断；属预期丢弃，不会踢人 |
| 客户端连上就被踢 `Invalid custom payload payload!` | 这是 Paper 对「客户端声明的自定义载荷」的校验（PaperMC/Paper#4201），与本插件无关；对策是客户端侧的通道白名单，或服务端 `-Dpaper.disableChannelLimit=true` |
| 引用块只在引用者自己那里显示 | 说明 `chat_meta` 没送达其他人：检查是否所有玩家都装了 E33Chat 客户端；`recipients=` 是否为在线人数 |

## MVP 边界

- **只做引用同步**。`@` 提及（Fabric 端由 `ServerMessageEvents.CHAT_MESSAGE` 解析）、私聊、历史记录、
  媒体上传、服务端配置界面都要额外的服务端逻辑，Paper 侧未实现；这些功能在 Paper 服上退化为
  「客户端本地可用，跨玩家不联动」。
- `mentionTargets` 恒为空列表（协议字段保留，编码正确，只是不填）。

## 目录

```
build.gradle / settings.gradle / gradle.properties
src/main/resources/paper-plugin.yml            # name/version/main/api-version
src/main/java/com/niuqu/chatbubble/paper/
    E33ChatPaperPlugin.java                    # 入口：通道注册、去重、广播
    McCodec.java                               # VarInt + string 的原版线格式复刻
    QuoteSyncPayload.java                      # C2S 解码（4 字段）
    ChatMetaPayload.java                       # S2C 编码（7 字段）
verify/
    com/niuqu/chatbubble/paper/CodecByteCheck.java  # 与原版 PacketByteBuf 的逐字节比对
    probe/ChannelProbe.java                    # 用 paper-api 自己的校验器验通道名
    run-verify.ps1                             # 一键跑上面的比对（ASCII-only，免 BOM 问题）
```
