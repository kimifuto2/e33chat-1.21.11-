# E33ChatPaper

E33Chat 的 **Paper 服务端插件**（引用跨玩家同步 + @提及同步 + **群聊** + **玩家动作转发**）。装了它的
Paper 服务器可以和已有的 Fabric 客户端互通，**客户端一行都不用改**。

```
Fabric 客户端 --C2S e33chat:quote_sync--> E33ChatPaper --S2C e33chat:chat_meta--> 所有在线玩家（含发送者）
玩家聊天（AsyncChatEvent）--本地解析 @名字--------^

Fabric 客户端 --C2S e33chat:group_*（8 条）--> GroupRegistry --S2C group_sync / group_message_relay /
                                                              group_event / group_invite_event--> 目标玩家
Fabric 客户端 --C2S e33chat:player_action--> PlayerActionRelay --S2C player_action_relay--> 范围内的其它玩家
```

服务端做的事：解码自定义载荷 → 维护**服务端权威**的群状态（群名/创建者/成员/pending 邀请）→
按范围广播编码后的载荷。群状态**落盘到 `<主世界>/serverconfig/e33chat-groups.json`**
（与 Fabric 端同一路径、同一 JSON 结构，重启不丢群）。不碰世界数据/实体/命令树，所以同一套线格式
可以直接搬到 Bukkit 的插件通道 API 上。

> **版本配对**：插件 3.1.0 对应客户端 **3.1.0**。群聊与动作是 3.1.0 新增的协议，3.0.x 客户端不认识
> 这些通道（会安全丢弃，不会踢人，但功能不生效）。3.0.x 的引用/@提及功能在 3.1.0 插件上照旧可用。

## 线格式（与 Fabric 侧逐字节一致）

`string` = `VarInt(UTF-8 字节数)` + UTF-8 字节（= Minecraft `StringEncoding.encode/decode`，
默认上限 32767 字符 / 98301 字节）。`list` = `VarInt(元素数)` + 逐元素编码。
`UUID` = **两个大端 long（16 字节）**，不是字符串（`PacketByteBuf.writeUuid` 的字节码就是
`writeLong(msb); writeLong(lsb)`）。`boolean` = 单字节 `0x00 / 0x01`（不是 VarInt）。

### 3.0.x 既有两条

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

### 3.1.0 群聊（9 进 4 出）

| # | `e33chat:group_create`（C2S） | 类型 |
|---|---|---|
| 1 | groupName | string（服务端夹到 32 字符；空串保留为「未命名」） |
| 2 | members | list\<UUID\>：`VarInt n` + n × UUID（**被邀请者**，不含发起者；服务端自动把发起者加进去） |

| # | `e33chat:group_message`（C2S） | 类型 |
|---|---|---|
| 1 | groupId | string（群 UUID 字符串） |
| 2 | content | string（服务端夹到 512 字符） |
| 3 | msgHash | string（明文 `hashCode()` 十进制串；不是十进制串时服务端照 Fabric 逻辑自己哈希） |

> ⚠️ 这条是 **3 个**字符串，不是 2 个 —— 少读一个会让后面的字段全部错位（Fabric 的
> `GroupMessagePayload` 就带 `msgHash`）。

| # | `e33chat:group_invite`（C2S） | 类型 |
|---|---|---|
| 1 | groupId | string |
| 2 | members | list\<UUID\>（待邀请者） |

| # | `e33chat:group_invite_respond`（C2S） | 类型 |
|---|---|---|
| 1 | groupId | string |
| 2 | accept | boolean（单字节） |

| # | `e33chat:group_kick`（C2S） | 类型 |
|---|---|---|
| 1 | groupId | string |
| 2 | target | **UUID（16 字节）** |

| # | `e33chat:group_leave`（C2S） | 类型 |
|---|---|---|
| 1 | groupId | string |

| # | `e33chat:group_rename`（C2S） | 类型 |
|---|---|---|
| 1 | groupId | string |
| 2 | name | string（服务端夹到 32 字符；空串 = 恢复默认「【群聊】」） |

| # | `e33chat:group_disband`（C2S） | 类型 |
|---|---|---|
| 1 | groupId | string |

| # | `e33chat:group_sync`（S2C） | 类型 |
|---|---|---|
| 1 | groupId | string |
| 2 | name | string（夹 32） |
| 3 | creator | string（创建者 UUID 字符串，空串 = 未知） |
| 4 | members | list\<(UUID, string)\>：`VarInt n` + n ×（UUID + 名字夹 32），顺序 = 入群顺序 |

| # | `e33chat:group_message_relay`（S2C） | 类型 |
|---|---|---|
| 1 | groupId | string |
| 2 | senderUUID | **UUID（16 字节）** |
| 3 | senderName | string（夹 32） |
| 4 | content | string（夹 512） |
| 5 | msgHash | string（夹 64） |

| # | `e33chat:group_event`（S2C） | 类型 |
|---|---|---|
| 1 | groupId | string |
| 2 | kind | string（夹 16）：`INVITED` / `KICKED` / `LEFT` / `DISBANDED` / `RENAMED` / `INVITE_DENIED` |
| 3 | text | string（夹 32）：群名（`RENAMED` 时是新群名） |

| # | `e33chat:group_invite_event`（S2C） | 类型 |
|---|---|---|
| 1 | groupId | string |
| 2 | groupName | string（夹 32） |
| 3 | inviterName | string（夹 32，邀请者=创建者） |
| 4 | kind | string（夹 16）：`INVITE` / `ACCEPTED` / `DECLINED` |
| 5 | actorName | string（夹 32，被邀请者/回应者） |

> 需求文档的 S2C 一栏只列了 4 条，但 Fabric 端的邀请流程是 **pending（待回应）** 语义：
> 建群/加人只登记 pending，并给**被邀请者**发 `group_invite_event(kind=INVITE)`，对方点「接受」
> 才有 `group_invite_respond` 上行。少了这条通道，被邀请者永远收不到按钮 → 群永远只有创建者一个人。
> 所以它必须实现（字段顺序以 Fabric `GroupInviteEventPayload` 为准）。

### 3.1.0 动作（1 进 1 出）

| # | `e33chat:player_action`（C2S） | 类型 |
|---|---|---|
| 1 | targetUUID | string（可为空串，服务端回退按 targetName 查在线玩家） |
| 2 | targetName | string |
| 3 | verbId | string（`pat/poke/pinch/kick/rub/spray/hug/blow/custom`） |
| 4 | customText | string（仅 `verbId=custom` 时生效，服务端清洗 + 夹 12 字符） |
| 5 | content | string（服务端夹 256 字符） |
| 6 | scope | string（`public` / `group` / `private`） |
| 7 | groupId | string（仅 `scope=group` 有意义） |

| # | `e33chat:player_action_relay`（S2C） | 类型 |
|---|---|---|
| 1 | senderUUID | **UUID（16 字节）** |
| 2 | senderName | string |
| 3 | targetUUID | string |
| 4 | targetName | string |
| 5 | verbText | string（服务端解析出的权威词条） |
| 6 | content | string |
| 7 | scope | string |
| 8 | groupId | string |

> ⚠️ 这条的**第 1 个字段是 UUID 而不是字符串**：写成 UUID 字符串会让客户端从第 1 字节起全部错位
> （校验程序里专门断言了首字节是 `0x12` 而不是字符串长度前缀 `0x24`）。

字段顺序/类型不是照着文档抄的：`verify/` 下的离线校验程序拿 **yarn 1.21.11 的原版 `PacketByteBuf`**
当基准，双向比对（原版编码 → 本插件解码；本插件编码 → 与原版编码整条 `byte[]` 全等），
**101 项全过**。覆盖：`writeUuid` 的 16 字节大端布局、`boolean` 单字节、`group_sync` 非空成员表、
`group_message` 的第三个字符串、`group_kick` 的 UUID 字段、`player_action_relay` 首字段为 UUID、
超长群名/成员名在**同一位置**按同一上限夹断、以及畸形包的降级行为。

同一脚本的第二阶段还用 **paper-api 自己的 `StandardMessenger.validateAndCorrectChannel`** 逐个校验
16 个通道名（注册非法通道名会让 `registerIncomingPluginChannel` 抛异常 → 插件在 `onEnable` 里自杀，
所以这不是装饰性检查）：

```powershell
$env:JAVA_HOME = 'C:\BianChengJA\JDK\JDK25'      # 26.x 分支用 JDK25（paper-api class 主版本 69）
powershell -ExecutionPolicy Bypass -File verify\run-verify.ps1
# → checks run : 101 / failures : 0 / RESULT: ALL BYTE CHECKS PASSED
# → ACCEPT 16 个 e33chat:*（含负面对照 REJECT: Channel must contain : separator）
# → checks run : 107 / failures : 0 / RESULT: ALL GROUP/ACTION FLOW CHECKS PASSED
# → overall exit code = 0
```

> 阶段 1（字节比对）在本分支跑的是 **yarn 1.21.11 的 `PacketByteBuf`**（校验器 import 的就是这个名字）——
> 26.x 客户端用的是 mojmap 的 `FriendlyByteBuf`。两者原语语义已用 javap 核对一致
> （`writeUUID` = 两个 `writeLong`、`writeBoolean` = netty 单字节、`writeUtf` -> `Utf8String.write`
> = VarInt 字节长度 + UTF-8），且阶段 3 已与 paper-api 26.x 一起跑通；阶段 2/3 各分支都能直接跑。

第三个阶段的前 76 项覆盖的是**行为**（不是字节）：建群只把勾选的人放进 pending、accept 才进群、
未受邀者伪造 accept 无效、仅创建者能邀请/解散、任何成员能改名/踢人但创建者不可被踢、
群消息 relay 含发送者且 msgHash 会归一化、非成员收发一律丢弃、动作不回给发送者、
三种 scope 的收件人集合、未知 `verbId` / 空正文被拒。

后 31 项是**持久化**（用临时目录当世界目录，真跑「保存 → 模拟重启 → 读回」）：
文件不存在=空且不创建文件、建群后立刻写盘、原子写的 `.tmp` 不残留、JSON 字段名与 Fabric 一致
`id/name/creator/members/uuid`、中文与 `<&>` 原样落盘（对应 Gson `disableHtmlEscaping`）、
pending 邀请跨重启存活且登录时补发、接受/改名/踢人/解散在重启后各自保持（解散不复活）、
能读 Fabric 写出来的文件（无 `pendingInvites` 键、`creator` 为空串、空成员群被跳过）、
未知顶层键被忽略、坏文件只记日志不崩不覆盖、**保存失败时旧文件逐字节不变而内存态照常更新**。

## 构建

本机固定用工作区里的 Gradle 发行版与共享缓存：

```powershell
$env:GRADLE_USER_HOME = 'C:\AI\ds harness\工作区\聊天mod移植1.21.11\.gradle-home'
$env:JAVA_TOOL_OPTIONS = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7899 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7899'
$env:JAVA_HOME = 'C:\BianChengJA\JDK\JDK25'   # paper-api 26.x 的 class 主版本是 69（Java 25）
& 'C:\AI\ds harness\工作区\聊天mod移植1.21.11\gradle-dist\gradle-9.8.2\bin\gradle.bat' --no-daemon --console=plain build
```

产物：`build/libs/E33ChatPaper-26.2-3.1.0.jar`（**67,136 字节**，`paper-api` 是 `compileOnly`，不打进 jar）。

| 项目 | 值 |
|---|---|
| 目标服务端 | **Paper 26.2**（build 135；服务端需 **Java 25**） |
| `paper-api` 坐标 | `io.papermc.paper:paper-api:26.2.build.135-stable` |
| `paper-plugin.yml` 的 `api-version` | **`'26.2'`**（26.x 写 `<major>.<minor>`，**不是** `'1.21'`） |
| 编译工具链 | **JDK 25**（`options.release = 25`；paper-api 的 class 主版本是 69） |
| 真机验证 | 已在 **Paper 26.2 build 135** 加载通过（插件 3.1.0 enabled + 10 进 6 出通道注册 + 群状态写入/重启读回 `<world>/serverconfig/e33chat-groups.json`） |

> 26.x 起版本坐标换了体系：`<mc>.build.<build>-<channel>`，**不再是** `1.21.11-R0.1-SNAPSHOT`。
> 26.2 / 26.3 / 1.21.11 三份的 `src/main/java` **完全相同**（同一套协议实现），差异只在
> `build.gradle`（paper-api 坐标 + `release`）与 `paper-plugin.yml` 的 `api-version`。

> 判据只看输出里的 `BUILD SUCCESSFUL`。PowerShell 会把 JVM 的 `Picked up JAVA_TOOL_OPTIONS`
> 当错误，退出码不可信；偶发毫秒级失败（daemon/锁竞争）隔 4 秒重试即可（最多 3 次）。

## 安装与测试

1. 把 `build/libs/E33ChatPaper-26.2-3.1.0.jar` 放进 **Paper 26.2**（build 135，服务端需 **Java 25**）的 `plugins/`，重启服务器。
2. 服务器日志应出现（4 行：群状态文件路径 + 一行摘要 + 两行完整通道清单）：
   ```
   [E33Chat] group store file = C:\...\world\serverconfig\e33chat-groups.json
   [E33Chat] E33ChatPaper enabled | in=10 | out=6 | dedupeWindow=10000ms | mentions=on(AsyncChatEvent) | groups=on(persisted, invites=pending) | actions=on(verbs=8, scopes=public/group/private)
   [E33Chat] channels in  = e33chat:quote_sync, e33chat:group_create, e33chat:group_message, e33chat:group_invite, e33chat:group_invite_respond, e33chat:group_kick, e33chat:group_leave, e33chat:group_rename, e33chat:group_disband, e33chat:player_action
   [E33Chat] channels out = e33chat:chat_meta, e33chat:group_sync, e33chat:group_message_relay, e33chat:group_event, e33chat:group_invite_event, e33chat:player_action_relay
   ```
   紧随其后还有一行群状态加载结果（首次开服是 `store ready (no file yet)`，之后是
   `store loaded | groups=N | pending=M | file=...`）。若看到 `group store disabled`，说明插件加载时
   世界目录还没就绪 —— 本次运行退化为纯内存态（重启丢群），请检查服务端日志里的世界加载顺序。
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

## 群聊（3.1.0）

Paper 侧实现的是 Fabric 端 `GroupRegistry` 的**同一条行为线**（不是另起一套规则）：
群状态由服务端权威维护，客户端只发操作、只按服务端下发的快照显示。

| 操作 | 谁能做 | 服务端行为 |
|---|---|---|
| 建群 `group_create` | 任何玩家 | 建群（创建者自动进群）→ 对每个勾选的玩家登记 pending 并发 `group_invite_event(INVITE)` → 给在线成员广播 `group_sync` |
| 发消息 `group_message` | 群成员 | 校验成员 → 补 `msgHash`（不是十进制串就用 `content.hashCode()`）→ 把 `group_message_relay` 发给**全体在线成员（含发送者）** |
| 邀请 `group_invite` | **仅创建者** | 登记 pending 并发 `group_invite_event(INVITE)`；非创建者收到 `group_event(INVITE_DENIED)`，群不变 |
| 回应邀请 `group_invite_respond` | 被邀请者 | 我确实在 pending 里且 accept=true 才真正进群（广播 `group_sync` + 给自己 `group_event(INVITED)` + 给创建者 `group_invite_event(ACCEPTED)`）；拒绝只回执 `DECLINED` |
| 踢人 `group_kick` | **任何群成员** | 目标必须在群里且**不是创建者**；被踢者收到 `group_event(KICKED)`，其余人收到新 `group_sync` |
| 改名 `group_rename` | **任何群成员** | 夹 32 字符；广播 `group_sync` + 全员 `group_event(RENAMED)` |
| 退群 `group_leave` | 群成员 | 自己收到 `group_event(LEFT)`；群空了就删群；否则广播新 `group_sync` |
| 解散 `group_disband` | **仅创建者** | 删群 + pending；给全体在线成员 `group_event(DISBANDED)` |
| 登录 | — | `PlayerJoinEvent` 时把该玩家所属的全部群逐个补发 `group_sync` |

关键实现点：

- **只有创建者能邀请/解散**，**改名/踢人所有成员可**，**创建者不可被踢** —— 三条都与 Fabric
  `GroupRegistry` 的校验分支一一对应（不是重新设计的权限模型）。
- **邀请是 pending 语义**：`create` / `invite` 都**不直接把人拉进群**，只登记 `pendingInvites`
  （groupId → 待回应者集合）+ 发邀请事件；`group_invite_respond` 才是唯一的入群入口。
  因此客户端伪造一条 accept 也进不来别人的群（服务端按当前登录玩家身份核对 pending）。
- 人数上限 `MAX_MEMBERS = 64`，按「成员 + pending」一起算。
- 被邀请者**离线时直接忽略**（不排离线队列）—— 与 Fabric 端同一条 MVP 取舍。
- 所有操作都在 `synchronized` 的方法里改内存态，且外层整体 try/catch：坏包/异常只记日志，
  **绝不影响连接**（Paper 里异常冒到插件消息处理器会直接断开玩家）。
- **群状态与 pending 邀请都落盘**，每次变更立刻保存，重启后自动读回（见下一节）。

### 群聊持久化（3.1.0 起）

| 项目 | 值 |
|---|---|
| 文件路径 | `<世界根目录>/serverconfig/e33chat-groups.json`（代码里 = `getWorlds().get(0).getWorldFolder()` 的 world 根 + `serverconfig/e33chat-groups.json`；与 Fabric 的 `LevelResource.ROOT` 语义相同） |
| 格式版本 | `"version": 1`（与 Fabric 同值，读取时不校验，容忍更高版本） |
| 加载时机 | 插件 `onEnable`（世界已加载完毕）——文件不存在 = 空，**不会**顺手创建文件 |
| 保存时机 | 建群 / 邀请发出 / 接受或拒绝邀请 / 踢人 / 退群 / 改名 / 解散 / 群空自动删除 / 登录时清理失效 pending，以及 `onDisable` 再保存一次 |
| 写盘方式 | 先写同目录 `e33chat-groups.json.tmp`（`FileChannel.force` 刷盘）→ `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 原子替换；不支持原子改名时退化为普通替换。失败只记日志，旧文件保持完整 |
| 日志 | `group store file = <绝对路径>` / `store loaded \| groups=N \| pending=M \| file=...` / `store saved \| via=create \| groups=N \| pending=M \| bytes=N \| file=...` / 失败时 `store load failed` / `store save failed` |

> **26.x 的世界目录布局差异（本分支实测）**：Paper 26.3 起世界目录是
> `<server>/world/dimensions/minecraft/overworld` 这种逐维度布局，`getWorldFolder()` 返回的是
> **维度目录**（`.../world/dimensions/minecraft/overworld`），而 Fabric 端 `LevelResource.ROOT`
> 仍是 `<server>/world`。代码因此按 **`level.dat` 上溯**到世界根（`findLevelRoot`），
> 保证落盘位置还是 `<server>/world/serverconfig/e33chat-groups.json`，与 Fabric 同一条路径。
> 1.21.11 分支上第一层就命中 `level.dat`，行为与直接用 `getWorldFolder()` 完全相同。
> 启动日志里的 `group store file = ...` 会打印最终路径（若与 `getWorldFolder()` 不同，还会多打一段
> `| levelRoot=`）。

JSON 结构与 Fabric 端**逐字段一致**（Fabric 的 `GroupRegistry.saveToDisk()` 用 Gson 写同一份）：

```json
{
  "version": 1,
  "groups": [
    {
      "id": "群 UUID",
      "name": "群名（空串 = 未命名）",
      "creator": "创建者 UUID（未知时是空串）",
      "members": [
        { "uuid": "成员 UUID", "name": "成员最后已知名字" }
      ]
    }
  ],
  "pendingInvites": [
    { "group": "群 UUID", "players": ["还没回应邀请的玩家 UUID"] }
  ]
}
```

- `members` 的顺序 = 入群顺序（`LinkedHashMap`），Fabric 读回来也是同一个顺序。
- 字段名 `id / name / creator / members / uuid` 是 Fabric Gson 的字面键，**改一个两边就不兼容**。
- `pendingInvites` 是 Paper 的**额外顶层字段**：Fabric 的读取器只看 `groups`，多余键会被忽略，
  所以 Paper 写出的文件 Fabric 能直接读；反过来 Fabric 保存时会把 `pendingInvites` 丢掉
  （= pending 邀请失效，群本身不受影响）。只有「同一个世界目录先后被 Fabric 服和 Paper 服打开」
  才会遇到这种差异。
- 字符串按 Gson `disableHtmlEscaping` 的语义转义（`< > & = '` 不转义，只有引号/反斜杠/控制字符/
  U+2028/U+2029 转义），中文与 emoji 以 UTF-8 原样落盘；手改文件带不带 UTF-8 BOM 都能读。
- 读取容错与 Fabric 一致：单个群/单个成员坏掉只丢那一条，**成员表为空的群不加载**，
  整个文件损坏只记 `store load failed` 并以空状态启动（**不会覆盖**坏文件，方便运维手工修）。

> ⚠️ **这个文件是服务端机密**：里面有玩家 UUID 与群名，**不要**放进 Web 可访问目录或随整合包分发。
> 它在**世界目录里**，所以会跟着地图存档一起备份/回档 —— 换周目/回档会连群状态一起回退；
> 想保留群状态就单独备份这个文件。

### 已知限制

- **多服/跨服不共享**：文件在各自的世界目录里，Velocity/Bungee 多子服、或两台 Paper 服
  各有一份，互相不同步；同一世界目录同时只能被一个服务端打开（`session.lock` 冲突）。
- **Fabric 与 Paper 不要同时用同一份文件**：都能读，但 Fabric 每次保存都会丢掉 `pendingInvites`
  这个 Paper 扩展字段（群与成员不受影响）。
- 离线成员仍然占成员位、能收到 `group_sync` 快照，但**收不到离线期间的群消息**（无离线补发）。
- pending 邀请只补发**「发出去过」的邀请**：被邀请者当时离线 → 仍然直接忽略（与 Fabric 同），
  不会在他上线时凭空生成一条邀请。

### 验现象

1. 两个 3.1.0 客户端连同一 Paper 服，A 建群并勾选 B：
   - 服务端日志 `group_create recv | player=A | name='...' | members=1` +
     `invites dispatched | group=... | inviter=A | sent=1 | pending=1`；
   - B 在私聊里看到「A 邀请你加入 xxx」+ 接受/拒绝按钮；
2. B 点「接受」→ 日志 `group_invite_respond recv | ... | accept=true` +
   `invite accepted | player=B | Group{...members=2...}`；A/B 双方侧边栏都出现该群；
3. A 发一条群消息 → `message | from=A | ... | recipients=2 | hash=... | preview=...`，
   两人气泡里都出现（发送者按 msgHash 去重，不会重复）；
4. B 改群名 → 两人侧边栏同步改名；B 踢 A → 日志 `kick denied | target is creator`（A 是创建者）；
5. A 解散 → 两人都收到 `DISBANDED`，侧边栏群消失。
6. **重启服务端**（正常 `stop`，不是强杀）：日志里先是
   `store saved | via=disable | groups=N | ...`，再启动时是 `store loaded | groups=N | pending=M | file=...`；
   A/B 重新上线后侧边栏里的群**还在**，改名/踢人/退群的结果也都保持（解散的群不会复活）。
7. 重启时如果 B 还没点「接受」：B 一上线会**重新收到那条邀请**（日志 `invite replayed | player=B | invites=1`）。

## 玩家动作转发（3.1.0，拍了拍等）

C2S `player_action` → 校验 → S2C `player_action_relay`。**不回给发送者**：发送者本地立即回显，
回给他是重复消息（所以服务端不做去重表）。

| 校验项 | 规则 |
|---|---|
| 词条 | **只信服务端自己的表**：8 个内置 `verbId`（`pat/poke/pinch/kick/rub/spray/hug/blow`）→ 固定中文词条，**忽略客户端 `customText`**（防伪造）；`verbId=custom` 才用自定义词，清洗后夹 12 字符；未知 id 直接丢弃 |
| 正文 | 夹 256 字符、去控制字符；空白丢弃 |
| 目标 | 先按 `targetUUID` 找；UUID 无效/nil 时按 `targetName` 在**在线玩家名**里精确匹配（`getPlayerExact`），仍找不到就丢弃（不猜） |
| 目标显示名 | 在线目标用服务端解析出的正式名；离线目标才用客户端带上来的名字（清洗后夹 32 字符） |
| `scope=public` | 发给全服在线玩家（除发送者） |
| `scope=group` | 群必须存在且**发送者是成员**，只发在线成员（除发送者）；否则只记日志、不广播 |
| `scope=private` | 只发被操作玩家（离线 → 无人收到，只剩发送者本地回显） |
| 未知 `scope` | 按 `public` 兜底（与 Fabric `PlayerActionRelay` 同） |

日志形如：

```
[E33Chat] player_action recv | player=A | target=Bob | verb=pat | scope=public | len=6
[E33Chat] srv | action | relay | from=A | target=Bob | verb=拍了拍 | scope=public | recipients=1 | preview=拍了拍的正文
```

## 排错

| 现象 | 原因 / 处理 |
|---|---|
| 日志没有 `E33ChatPaper enabled` | jar 没被加载：确认是 `paper-plugin.yml` 在 jar 根目录、`main` 类存在；看服务器启动日志里的插件加载错误 |
| 有 `quote_sync recv` 但没有 `chat_meta broadcast` | 命中去重（10 秒内同 `uuid\|msgHash\|quote`）——日志里会是 `chat_meta skipped (dup)` |
| 有 `mention broadcast` 但没有对应的 `chat_meta broadcast` | 命中去重（10 秒内同 `uuid\|msgHash\|mention`）——日志里是 `mention skipped (dup)` |
| 发了 `@名字` 但日志里完全没有 `mention broadcast` | ① 名字不在线/拼错；② `@` 后面紧跟的是空格；③ 该消息被别的插件取消（`ignoreCancelled` 直接跳过）；④ 服务端日志级别过滤掉了 INFO |
| 日志有 `mention broadcast`，客户端却没提醒 | 客户端提醒是**扫正文**的：确认双方都装 E33Chat、客户端的横幅/音效开关开着，且正文里的 `@名字` 没被聊天插件改写（例如插入了 `§` 码或零宽字符） |
| `quote_sync dropped \| ... IllegalArgumentException` | 客户端与本插件的协议不一致或包被截断；属预期丢弃，不会踢人 |
| 客户端连上就被踢 `Invalid custom payload payload!` | 这是 Paper 对「客户端声明的自定义载荷」的校验（PaperMC/Paper#4201），与本插件无关；对策是客户端侧的通道白名单，或服务端 `-Dpaper.disableChannelLimit=true` |
| 引用块只在引用者自己那里显示 | 说明 `chat_meta` 没送达其他人：检查是否所有玩家都装了 E33Chat 客户端；`recipients=` 是否为在线人数 |
| 群聊功能完全不生效（无 `group_create recv`） | ① 客户端不是 3.1.0（3.0.x 不认识这些通道）；② 该服务器地址没进客户端白名单（公网地址见「安装与测试」第 3 条）；③ 其它插件抢先注册了同名通道 |
| 建群后只有创建者一个人在群里 | 邀请是 **pending** 语义：被邀请者必须在私聊里点「接受」。看日志有没有 `invites dispatched ... sent=N`（`sent=0` 说明对方**离线**或已是成员/已在 pending） |
| 日志有 `invite skipped (offline)` | 被邀请者不在线：Paper 端与 Fabric 端一样**不排离线队列**，需要等他上线后再邀请 |
| 群聊在服务端重启后全没了 | 先看日志有没有 `store load failed`（坏文件/无读权限 → 以空状态启动）和 `group store disabled`（加载时世界目录没就绪，本次运行纯内存）；再确认服务端是否用 `stop` 正常关闭（强杀只会丢最后一次未写盘的变化，已保存的群仍在） |
| 日志有 `store save failed` | 世界目录不可写（磁盘满/只读/杀软占用）：内存态照常可用，但重启会回退到上一次成功保存的内容；修好写权限后随便做一次群操作即可重新落盘 |
| 重启后群回来了但成员表是旧的 | 最后一次变更没成功落盘（看 `store save failed`）；文件是「要么旧、要么新」，不会出现半截 JSON |
| 想手工删掉某个群 | 停服后编辑 `<world>/serverconfig/e33chat-groups.json` 再启服；手改坏了只会 `store load failed`（文件不会被覆盖），删掉该文件即恢复「没有群」 |
| 踢人日志是 `kick denied \| target is creator` | 预期：创建者不可被踢，只能他自己退群或解散 |
| 改名/邀请日志是 `invite denied \| actor is not creator` | 预期：只有创建者能邀请/解散；改名/踢人所有成员可以 |
| 动作消息日志有 `rejected \| unknown verb` | 客户端发来的 `verbId` 不在 8 个内置 id 里且不是 `custom`；属预期拒绝（防伪造） |
| `player_action_relay` 客户端显示乱码 | 说明字段顺序不对：注意第 1 个字段是 **UUID（16 字节）**，不是 UUID 字符串 |

## 边界

- **已实现**：
  - 引用跨玩家同步（`quote_sync` → `chat_meta`）、@提及解析与广播
    （`AsyncChatEvent` → `chat_meta.mentionTargets`）；
  - **群聊**：建群 / 邀请（pending）/ 回应邀请 / 发消息 / 改名 / 踢人 / 退群 / 解散 / 登录补发；
  - **玩家动作转发**：8 个内置动作 + 自定义词条，`public` / `group` / `private` 三种范围。
- **未实现**：私聊消息、聊天历史记录、媒体上传、服务端配置界面 —— 这些功能在 Paper 服上退化为
  「客户端本地可用，跨玩家不联动」。
- **群状态持久化**到 `<主世界>/serverconfig/e33chat-groups.json`（与 Fabric 同路径同结构），
  pending 邀请也一并落盘；跨服/多服不共享，文件随世界目录备份（见「群聊 / 群聊持久化」）。
- `mentionTargets` 只是协议字段：客户端当前的提及提醒是**扫正文**实现的，填不填都不改变提醒效果
  （详见「@提及同步 / 已知限制」）。
- 不改 Fabric 客户端：本插件只发 S2C 载荷，字段顺序/类型与原版 `PacketByteBuf` 逐字节一致
  （由 `verify/` 的 101 项离线校验保证）。

## 目录

```
build.gradle / settings.gradle / gradle.properties
src/main/resources/paper-plugin.yml            # name/version/main/api-version: '26.2'
src/main/java/com/niuqu/chatbubble/paper/
    E33ChatPaperPlugin.java                    # 入口：通道注册、派发、去重、@提及事件、登录补发
    E33Channels.java                           # 全部 10 进 6 出通道名（协议常量的唯一出处）
    MentionParser.java                         # @名字 解析（大小写不敏感 / 最长匹配 / 去重）
    McCodec.java                               # 原版线格式复刻：VarInt / string / long / UUID / boolean
    QuoteSyncPayload.java                      # C2S quote_sync 解码（4 字段）
    ChatMetaPayload.java                       # S2C chat_meta 编码（7 字段）
    GroupWire.java                             # 群/动作协议常量 + 夹断 + UUID 解析 + hash 归一化
    GroupInbound.java                          # C2S group_* 8 条解码（记录类型 + 降级）
    GroupOutbound.java                         # S2C group_* 4 条编码
    GroupRegistry.java                         # 群状态机（成员/创建者/pending 邀请/广播/落盘时机）
    GroupStore.java                            # 落盘层：<world>/serverconfig/e33chat-groups.json（JSON + 原子写）
    PlayerActionPayloads.java                  # C2S player_action 解码 + S2C relay 编码
    PlayerActionVerbs.java                     # 8 个动作的权威词条表 + 清洗
    PlayerActionScope.java                     # public / group / private
    PlayerActionRelay.java                     # 动作范围校验 + 广播（不回发送者）
verify/
    com/niuqu/chatbubble/paper/CodecByteCheck.java  # 与原版 PacketByteBuf 的逐字节比对（101 项）
    com/niuqu/chatbubble/paper/GroupFlowCheck.java  # 假玩家驱动群聊/动作状态机 + 持久化（107 项）
    probe/ChannelProbe.java                    # 用 paper-api 自己的校验器验通道名
    run-verify.ps1                             # 一键跑上面三阶段（ASCII-only，免 BOM 问题）
```

