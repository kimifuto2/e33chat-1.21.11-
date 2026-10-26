# E33ChatPaper

E33Chat 的 **Paper 服务端插件**（引用跨玩家同步 + @提及同步）。装了它的 Paper 服务器可以和已有的
Fabric 客户端互通，**客户端一行都不用改**。

```
Fabric 客户端 --C2S e33chat:quote_sync--> E33ChatPaper --S2C e33chat:chat_meta--> 所有在线玩家（含发送者）
玩家聊天（AsyncChatEvent）--本地解析 @名字--------^
```

服务端做四件事：解码 4 个字符串 → 从聊天明文里解析 `@名字` → 按 `<uuid>|<msgHash>|<kind>` 做 10 秒去重 →
编码 7 个字段后广播。不碰世界数据/实体/命令树，所以同一套线格式可以直接搬到 Bukkit 的插件通道 API 上。

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
| 6 | mentionTargets | list\<string\> | `VarInt(元素数)` + 逐元素 string；元素是**纯玩家名**（不带 `@`、不带 UUID），见「@提及同步」 |
| 7 | quotedHash | string | 同上 |

字段顺序/类型不是照着文档抄的：`verify/` 下的离线校验程序拿 **yarn 1.21.11 的原版 `PacketByteBuf`**
当基准，双向比对（原版编码 → 本插件解码；本插件编码 → 与原版编码整条 `byte[]` 全等），34 项全过
（其中 `chat_meta+mentions byte-identical` 已经覆盖**非空** `mentionTargets` 列表）。

## 构建

本机固定用工作区里的 Gradle 发行版与共享缓存：

```powershell
$env:GRADLE_USER_HOME = 'C:\AI\ds harness\工作区\聊天mod移植1.21.11\.gradle-home'
$env:JAVA_TOOL_OPTIONS = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7899 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7899'
& 'C:\AI\ds harness\工作区\聊天mod移植1.21.11\gradle-dist\gradle-9.5.1\bin\gradle.bat' --no-daemon --console=plain build
```

产物：`build/libs/E33ChatPaper-26.2-3.0.1.jar`（**15,315 字节**，`paper-api` 是 `compileOnly`，不打进 jar）。

| 项目 | 值 |
|---|---|
| 目标服务端 | **Paper 26.2**（build 135；服务端需 **Java 25**） |
| `paper-api` 坐标 | `io.papermc.paper:paper-api:26.2.build.135-stable` |
| `paper-plugin.yml` 的 `api-version` | **`'26.2'`**（26.x 写 `<major>.<minor>`，**不是** `'1.21'`） |
| 编译工具链 | **JDK 25**（`options.release = 25`；paper-api 的 class 主版本是 69） |
| 真机验证 | 已在 **Paper 26.2 build 135** 加载通过 |

> 26.x 起版本坐标换了体系：`<mc>.build.<build>-<channel>`，**不再是** `1.21.11-R0.1-SNAPSHOT`；
> 旧的 `api.papermc.io/v2` 对 26.x 已 sunset，查最新 build 用 `fill.papermc.io/v3`。

> 版本线：26.2 / 26.3 两分支的业务代码与 1.21.11 版**零改动**，两个 jar 的 6 个 `.class` **逐字节相同**
> （差异只在 `META-INF/MANIFEST.MF` 与 `paper-plugin.yml` 的 `api-version`）；验收日志见下方「安装与测试」第 2 步。

> 判据只看输出里的 `BUILD SUCCESSFUL`。PowerShell 会把 JVM 的 `Picked up JAVA_TOOL_OPTIONS`
> 当错误，退出码不可信；偶发毫秒级失败（daemon/锁竞争）隔 4 秒重试即可（最多 3 次）。

## 安装与测试

1. 把 `build/libs/E33ChatPaper-26.2-3.0.1.jar` 放进 **Paper 26.2** 服务器（build 135，**服务端需 Java 25**）的 `plugins/`，重启服务器。
2. 服务器日志应出现：
   ```
   [E33Chat] E33ChatPaper enabled | in=e33chat:quote_sync | out=e33chat:chat_meta | dedupeWindow=10000ms | mentions=on(AsyncChatEvent)
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
   [E33Chat] chat_meta broadcast | from=A | msgHash=... | mentions=0 | via=quote_sync | recipients=2
   ```
   玩家 B（以及 A 自己）的气泡里应出现引用块。

## @提及同步

### 行为

聊天事件（`io.papermc.paper.event.player.AsyncChatEvent`）里把 `event.message()` 序列化成纯文本，
按在线玩家名做**大小写不敏感的最长匹配**，命中后广播一条 `chat_meta`（`quote*` 三个字段留空、
`quotedHash` 留空）：

```
[E33Chat] mention broadcast | from=Alice | targets=[Bob] | msgHash=123456789
[E33Chat] chat_meta broadcast | from=Alice | msgHash=123456789 | mentions=1 | via=chat_message_mentions | recipients=2
```

规则（与客户端 `MentionDetector` / `Names`、以及 Fabric 服务端 `extractMentions` 对齐，不是自己发明的）：

| 规则 | 说明 |
|---|---|
| `@` 必须紧贴名字 | `@Bob` 命中；不带 `@` 的 `Bob` 不命中（对应客户端 `mentionRequireAt` 默认值） |
| 名字右侧必须是行尾或非名字字符 | 名字字符 = 字母/数字/`_`（中文也算字母）。`@Bob，你好` 命中，`@张三你好` **不**命中 |
| `@` 左侧不检查 | 与客户端一致：`mail@Bob` 同样算提及 |
| 大小写不敏感、输出规范名 | 输入 `@bobby` + 在线名 `Bobby` → `mentionTargets=["Bobby"]` |
| 最长匹配 | 同时存在 `Bob` / `Bobby` 时，`@Bobby` 只解析出 `Bobby`（不会同时/错误命中 `Bob`） |
| 去重、**不排除发送者自己** | 名单是 `LinkedHashSet`；客户端明确支持「自己 @ 自己」也弹提醒（`ChatBubbleScreen` 发送路径的 self-@ 分支），所以不排除 |
| 不特判 `@everyone` / `@here` | 只做「在线玩家名精确匹配」，没有同名玩家就不会命中，因此不会误报 |
| 名字可含空格 / 中文 | 匹配对原始文本做 `regionMatches`，不按「名字字符」切分，`@Bob Smith` 能整体命中（前提是服务端玩家名真叫这个） |

### 前提

- **广播时机**：Bukkit 的 `sendPluginMessage` / 在线玩家列表要求主线程，所以异步聊天回调里只取
  不可变快照（发送者 UUID/名字 + 纯文本），解析、去重、发包全部
  `getServer().getScheduler().runTask(plugin, ...)` 派发到主线程。
- `messageHash` = `String.valueOf(该消息明文.hashCode())`，明文取 `event.message()`；监听优先级用
  `MONITOR`（让其它聊天插件先改完 `event.message()`），与客户端 `ChatMessageStore.addMessage` 的
  `String.valueOf(content.getString().hashCode())` 是同一契约。
- **只在 `mentionTargets` 非空时广播**：不含 `@`、或 `@` 后没有命中在线玩家的消息完全不发包
  （事件回调里先用 `indexOf('@')` 提前返回，连调度任务都不产生）。
- **去重键区分种类**：引用 `uuid|msgHash|quote`、提及 `uuid|msgHash|mention`。同一条消息可以既发
  引用又发提及，两条路径不会互相吞掉（这也是把 MVP 的 `uuid|msgHash` 改成带 `kind` 的原因）。

### 已知限制 / 风险

- **其它插件改写消息会导致 hash 不匹配**：`messageHash` 用的是 `event.message()`。若某个聊天插件在
  `MONITOR` 之后改写消息、通过 `ChatRenderer` 往正文加前后缀、或把整行渲染结果塞进
  `PlayerChatMessage` 的正文，客户端算出的 hash 就对不上 → 客户端会忽略这条 meta（引用块贴不上）。
  由于 `mentionTargets` 目前不参与客户端的提及判定（下一条），这只影响该条的引用块，不影响提醒。
- **`mentionTargets` 在当前客户端只用于诊断**：客户端真正的高亮/横幅/音效来自
  `MentionDetector.isMentioned(消息正文, 本地玩家名, ...)`（`@` + 正文 + 大小写不敏感）；
  `ChatMessageStore#applyChatMeta` 只把 `mentionTargets` 记进 debug 日志、并随
  `EchoTracker.PendingMeta` 透传，**没有任何按 UUID / 名单匹配的逻辑**。所以 Paper 侧填这个字段是
  「协议对齐 + 供将来使用」；真正的提醒由客户端扫**正文**实现（正文本来就会原样到达客户端）。
- 只在**在线玩家名**里匹配：离线玩家、昵称插件的装饰名（前缀/称号）不算；`@` 后拼错一个字就不命中。
- 名字带空格/中文只在「服务端真实玩家名就是那样」时生效（原版用户名是 `[A-Za-z0-9_]{3,16}`）。

### 验现象

1. 两个都装 E33Chat 的客户端连同一 Paper 服，B 的名字是 `Bob`。
2. A 发 `@Bob 在吗`：
   - 服务端日志出现 `mention broadcast | from=A | targets=[Bob] | msgHash=...` 与
     `chat_meta broadcast | ... | mentions=1 | via=chat_message_mentions | recipients=2`；
   - B 的客户端应弹提及横幅/提示音，聊天界面里 `@Bob` 高亮；
   - A 自己也会弹（客户端支持 self-@）。
3. A 发 `@Bob2 在吗`（没有叫 `Bob2` 的玩家）→ 服务端**没有** `mention broadcast` 行。
4. A 发 `@bob 在吗`（小写）→ 日志里是 `targets=[Bob]`（规范名），即大小写不敏感。
5. A 发 `@张三 在吗`（有中文名玩家）→ `targets=[张三]`；发 `@张三你好` → 不命中（右侧是名字字符）。
6. 同一条消息先用引用再 @ 别人，应分别看到 `via=quote_sync` 与 `via=chat_message_mentions` 两条
   `chat_meta broadcast`（去重键种类不同，互不吞掉）。

## 排错

| 现象 | 原因 / 处理 |
|---|---|
| 日志没有 `E33ChatPaper enabled` | jar 没被加载：确认是 `paper-plugin.yml` 在 jar 根目录、`main` 类存在；看服务器启动日志里的插件加载错误 |
| 有 `quote_sync recv` 但没有 `chat_meta broadcast` | 命中去重（10 秒内同 `uuid\|msgHash\|quote`）——日志里会是 `chat_meta skipped (dup)` |
| 有 `mention broadcast` 但没有对应的 `chat_meta broadcast` | 命中去重（10 秒内同 `uuid\|msgHash\|mention`）——日志里是 `mention skipped (dup)` |
| 发了 `@名字` 但日志里完全没有 `mention broadcast` | ① 名字不在线/拼错；② `@` 后面紧跟的是空格；③ 该消息被别的插件取消（`ignoreCancelled` 直接跳过）；④ 服务端日志级别过滤掉了 INFO |
| 日志有 `mention broadcast`，客户端却没提醒 | 客户端提醒是**扫正文**的：确认双方都装 E33Chat、客户端的横幅/音效开关开着，且正文里的 `@名字` 没被聊天插件改写（例如插入了 `§` 码或零宽字符） |
| `quote_sync dropped \| ... IllegalArgumentException` | 客户端与本插件的协议不一致或包被截断；属预期丢弃，不会踢人 |
| 客户端连上就被踢 `Invalid custom payload payload!` | 这是 Paper 对「客户端声明的自定义载荷」的校验（PaperMC/Paper#4201），与本插件无关；对策是客户端侧的通道白名单，或服务端 `-Dpaper.disableChannelLimit=true`（Paper 默认通道上限 128、实际可用 127，大整合包必加） |
| 引用块只在引用者自己那里显示 | 说明 `chat_meta` 没送达其他人：检查是否所有玩家都装了 E33Chat 客户端；`recipients=` 是否为在线人数 |

## 边界

- **已实现**：引用跨玩家同步（`quote_sync` → `chat_meta`）、@提及解析与广播
  （`AsyncChatEvent` → `chat_meta.mentionTargets`）。
- **未实现**：私聊、历史记录、媒体上传、服务端配置界面 —— 这些功能在 Paper 服上退化为
  「客户端本地可用，跨玩家不联动」。
- `mentionTargets` 只是协议字段：客户端当前的提及提醒是**扫正文**实现的，填不填都不改变提醒效果
  （详见「@提及同步 / 已知限制」）。
- 不改 Fabric 客户端：本插件只发 `e33chat:chat_meta`，字段顺序/类型与原版 `PacketByteBuf` 逐字节一致。

## 目录

```
build.gradle / settings.gradle / gradle.properties
src/main/resources/paper-plugin.yml            # name/version/main/api-version: '26.2'
src/main/java/com/niuqu/chatbubble/paper/
    E33ChatPaperPlugin.java                    # 入口：通道注册、去重、@提及事件、广播
    MentionParser.java                         # @名字 解析（大小写不敏感 / 最长匹配 / 去重）
    McCodec.java                               # VarInt + string 的原版线格式复刻
    QuoteSyncPayload.java                      # C2S 解码（4 字段）
    ChatMetaPayload.java                       # S2C 编码（7 字段）
verify/
    com/niuqu/chatbubble/paper/CodecByteCheck.java  # 与原版 PacketByteBuf 的逐字节比对
    probe/ChannelProbe.java                    # 用 paper-api 自己的校验器验通道名
    run-verify.ps1                             # 一键跑上面的比对（ASCII-only，免 BOM 问题）
```
