# E33Chat · Minecraft 26.2 Fabric 版

> **本分支：`mc26.2` · Minecraft 26.2（26.x 移植线）**
> 产物：**`e33chat-Fabric-26.2-3.0.1.jar`**（543,103 字节 / ≈530 KiB） · 模组版本 **3.0.1**
> 仓库：[kimifuto2/e33chat-1.21.11-](https://github.com/kimifuto2/e33chat-1.21.11-) · 上游：[E33EPUS/E33Chat](https://github.com/E33EPUS/E33Chat)（MIT）
>
> 在 1.21.11 移植/增强版的基础上，把客户端**移植到 Minecraft 26.2**：功能集与 `main` 分支同源，主要差异在构建链与 26.x 平台 API（见「26.x 移植说明」）。

## 分支矩阵

三个分支同属一个仓库，产物互不通用 —— 请按自己的游戏版本取对应分支的 jar：

| 分支 | Minecraft | 定位 | 产物 jar（3.0.1） | 大小 |
|---|---|---|---|---|
| `main` | 1.21.11 | 稳定线 | `e33chat-Fabric-1.21.11-3.0.1.jar` | 539,286 B |
| `mc26.3` | 26.3 | 26.x 移植线 | `e33chat-Fabric-26.3-3.0.1.jar` | 543,126 B |
| **`mc26.2`**（本分支） | 26.2 | 26.x 移植线 | `e33chat-Fabric-26.2-3.0.1.jar` | 543,103 B |

> 三个分支都在 **<https://github.com/kimifuto2/e33chat-1.21.11->** 下。**26.x 移植线**指 Mojang 改用年份版本号之后的版本，构建链与 1.21.11 差异很大（Java 25 / Gradle 9.8+ / Loom 1.19.3 / **不再混淆**）；`mc26.3` 与本分支环境基本相同，只有 Minecraft / Fabric API / 输入体系（GLFW vs SDL）不同（见「26.x 移植说明」最后一节）。

---

## 构建环境（本分支实测值）

| 项目 | 版本 / 说明 |
|---|---|
| Minecraft | **26.2** |
| Java | **25**（`options.release = 25`，toolchain 25；26.2 与 26.3 同属 26.x，要求 Java 25） |
| Gradle | **9.8.2**（Loom 1.19.3 要求 **≥ 9.8**；仓库内 `gradlew` 仍指向 9.5.1，直接构建请用 Gradle 9.8.2） |
| Fabric Loom | **1.19.3**，插件 id **`net.fabricmc.fabric-loom`** → `disableObfuscation` 模式 |
| Mappings | **无** —— 26.x 的 jar 不再混淆，`build.gradle` 里**没有** `mappings` 行 |
| Fabric Loader | **0.20.1**（模组声明 `>=0.20.1`） |
| Fabric API | **`0.161.0+26.2`** |
| Mod Menu（可选） | **`19.0.0-alpha.1`**（`compileOnly`；模组建议 `>=19.0.0`） |
| 构建命令 | `gradle build`（Gradle 9.8.2） |

两点必须注意：

- **没有 mappings 行**：26.x 的 version json 不再提供 client/server mappings，声明任何 `mappings …` 都会让 Loom 直接报找不到官方 mappings；
- **依赖配置不用 `mod*`**：生态 jar 已是 Mojang 名，`modImplementation` / `modCompileOnly` 换成普通 `implementation` / `compileOnly`。

客户端模组（`environment: "*"`，含 `main` / `client` / `modmenu` 三个 entrypoint）。**服务端为可选**：服务端安装后可启用「服务器联动类功能」（消息元数据、精确引用跳转、@提及同步等）。

---

## 安装与使用

### 客户端（必需）

1. 建一个 **Minecraft 26.2 + Fabric Loader 0.20.1** 的实例；
2. 把 **Fabric API `0.161.0+26.2`** 放进实例的 `mods/`；
3. 把 **`e33chat-Fabric-26.2-3.0.1.jar`** 放进**同一个**实例的 `mods/` 目录；
4. （可选）装 **Mod Menu `19.0.0-alpha.1`**，即可获得图形化配置入口；
5. 启动游戏后聊天栏就是本模组的聊天界面，配置界面在 **Mod Menu → E33Chat → 配置**。

> 运行时 Java 由启动器提供（MC 26.x 自带 Java 25 运行时）；**只有自己构建**时才需要本机 JDK 25。

### 服务端（可选，装了才有联动）

| 服务端类型 | 装什么 | 效果 |
|---|---|---|
| Fabric 服务端 **26.2** | 本分支的 `e33chat-Fabric-26.2-3.0.1.jar` | 消息元数据 / 引用精确跳转 / @提及同步全部可用 |
| Paper | `paper-plugin/` 构建出的 `E33ChatPaper-3.0.1.jar`（目标 **Paper 1.21.11**） | 线协议与客户端一致；**本分支未在 Paper 26.x 上验证过该插件** |

---

## 26.x 移植说明

26.x 是 Mojang 换成**年份版本号**之后的第一批版本，平台变化远大于普通小版本。下面只列与本模组构建/运行直接相关的差异。

### 1. 构建链：从 Yarn + 混淆到「无 mappings + 不混淆」

| 项目 | 1.21.11（`main`） | 26.2（本分支） |
|---|---|---|
| 版本号 | `1.21.11` | `26.2`（年份版本号） |
| 混淆 / Mappings | 混淆，Yarn `1.21.11+build.6` | **jar 不再混淆 → 无 yarn/official mappings** |
| Loom 模式 | 正常 remap | **`disableObfuscation`**（Loom 按插件 id 判定） |
| Loom 插件 id | `fabric-loom` | **`net.fabricmc.fabric-loom`** |
| 依赖配置 | `modImplementation` / `modCompileOnly` | **`implementation` / `compileOnly`** |
| Loom 版本 | 1.17.19 | **1.19.3** |
| Gradle | 9.5.1 | **9.8.2**（Loom 1.19.3 要求 ≥ 9.8） |
| Java | 21 | **25**（`release 25`） |
| Fabric Loader | 0.19.3 | 0.20.1 |
| Fabric API | 0.141.5+1.21.11 | **0.161.0+26.2** |

对应到 `build.gradle` 就是这几处：

```gradle
plugins {
    // 26.x 必须用这个 id：Loom 据此把扩展设成 disableObfuscation=true
    id 'net.fabricmc.fabric-loom' version '1.19.3'
}

dependencies {
    minecraft "com.mojang:minecraft:26.2"
    // 注意：没有 mappings 行（26.x 没有 mappings 可声明）
    implementation "net.fabricmc:fabric-loader:0.20.1"
    implementation "net.fabricmc.fabric-api:fabric-api:0.161.0+26.2"
    compileOnly "com.terraformersmc:modmenu:19.0.0-alpha.1"
}
```

- 写错插件 id（用回 `fabric-loom`）或补一行 `mappings`，都会在配置阶段失败；
- 用回 `modImplementation` 不会报错但会走 remap 路径，在 26.x 上没有意义。

### 2. 输入层：26.3 起从 GLFW 换成 SDL（本分支仍是 GLFW）

26.2 与 26.3 的**输入常量值不同**，是这两个分支之间最容易踩的静默坑：

| 项目 | 本分支 26.2（GLFW 体系） | 26.3（SDL 体系） |
|---|---|---|
| `InputConstants.KEY_ESCAPE` | **256** | **41** |
| `KEY_RETURN` / `KEY_TAB` | 257 / 258 | 40 / 43 |
| `KEY_UP` / `KEY_NUMPADENTER` | 265 / 335 | 82 / 88 |
| 鼠标键号 | **`0`=左 / `1`=右 / `2`=中** | **`1`=左 / `2`=中 / `3`=右** |
| 鼠标按下状态 | `mouseHandler.isLeftPressed()` | 同左（`glfwGetMouseButton` 已不可用） |

> 上表数值取自两个版本 `minecraft-merged-deobf` jar 里 `com.mojang.blaze3d.platform.InputConstants` 的常量定义。

- **本分支（26.2）的键码与鼠标键号都还是 GLFW 体系**：从 1.21.11 直接搬来的裸数字（`256`/`257`/`258`、左键 `0`）在这个分支上是**正确**的；
- 但代码里**不要写死数字**：一律用 `InputConstants.KEY_*` 常量。升级到 26.3 时这些常量的值会整体换成 SDL（`KEY_ESCAPE` 变成 41），写死 `256` 的代码会在 26.3 上**编译通过但静默失灵**；
- 同理，26.3 的鼠标左键是 `1`：自造 `MouseButtonEvent` 时传 `0` 会被原版控件当成非左键（表现为「点了完全没反应」）；本分支传 `0` 才对；
- 组合键不要自己按位判断，用 `InputWithModifiers.isCopy()/isPaste()`、`hasControlDown()`。

### 3. GUI 渲染：render → extractRenderState

| 1.21.11 / 旧 API | 26.x |
|---|---|
| `Screen.render(...)` | **`extractRenderState(GuiGraphicsExtractor, …)`** |
| `Screen.renderBackground(...)` | **`extractBackground(...)`** |
| `Screen.shouldPause()` | **`isPauseScreen()`，且基类默认返回 `true`** |
| `HudRenderCallback`（Fabric） | **`HudElementRegistry.addLast(...)`**（`fabric-rendering-v1`；`HudRenderCallback` 已删除） |
| `Minecraft.setScreen(...)` | **`Gui.setScreen(...)`** |

- 26.x 的界面是「先提取渲染状态、再统一提交」，自绘控件/文本要写在 `extractRenderState` 里（子控件同样有 `extractRenderState`）；
- `isPauseScreen()` 默认 true ⇒ **聊天界面必须显式覆盖成 `false`**，否则打开聊天会把单人游戏暂停；
- 聊天文本的注入点也跟着改名：`ChatComponent.render` → `ChatComponent.extractRenderState(GuiGraphicsExtractor, Font, int, int, int, DisplayMode, boolean)`。

### 4. `mc26.3` 分支的差异

本分支（26.2）与 `mc26.3` 的构建链、Java、Loom、GUI/输入 API 基本一致，只是：

| 项目 | `mc26.2`（本分支） | `mc26.3` |
|---|---|---|
| Minecraft | 26.2 | 26.3 |
| Fabric API | `0.161.0+26.2` | `0.165.0+26.3` |
| 输入体系 | **GLFW**（`KEY_ESCAPE=256`，鼠标左键 `0`） | **SDL**（`KEY_ESCAPE=41`，鼠标左键 `1`） |

---

## 3.0.x 版本说明（相对上游 2.4.2）

当前版本 **3.0.1**。相对上游 2.4.2，本版除完成 1.21.11 → 26.2 的移植外，还包含以下增强：

| 类别 | 内容 |
|---|---|
| 免打扰 | **按会话独立**（公屏 / 每个私聊各一份）、玩家列表紫色月亮标记、名字过长省略、@提及例外 |
| 聊天小窗 | 聊天面板关闭时显示最近公屏消息（可配置静默淡出秒数、热更新、公屏免打扰时不弹出、跟随 F1）|
| 侧边栏 | 在线人数 / 单人状态行、**离线私聊玩家**（灰点）、点击玩家行不再跳回顶部 |
| 文本操作 | 任意聊天文本可拖选复制、**系统消息可复制**（右键只显示「复制」）、输入框拖选/全选/剪切 |
| 引用 | 双击引用跳转到原消息；**跨玩家引用同步**（服务端 hash 链路，他人也能看到引用条）|
| 提及 | `@名字` 跨玩家同步（Fabric 服务端与 Paper 插件同线格式）|
| 兼容 | 插件可点击文本（未知 `ClickEvent` 交回原版处理）、Jade 描述框避让、皮肤异步加载、主线程卡顿看门狗 |
| 网络 | 服务器白名单（按连接注册/注销通道）、**通道节食**开关、Paper 通道数量上限规避 |

### 通道节食（实验性，默认关闭）

位置：**配置界面 → 高级 → 网络与服务端 → 「通道节食（实验性）」**。

开启后，在**非 Fabric 服务器**（如 Paper）上不会把自定义通道声明发给服务端（`brand` 含 `fabric` 的服务器与单机/局域网不受影响），从而规避 Paper 的 `Too many channels registered!` 踢人问题。

> ⚠️ 该开关会同时压制**其它模组**的通道声明（其功能会静默失效），建议仅在进入 Paper 等严格服务端遇踢时临时开启。

---

## 与原版的主要区别

### 聊天界面

- **主题系统**：暗 / 浅色主题 + 6 种强调色 + 自定义 HEX 配色，配置界面跟随主题
- 保留原版气泡、引用块、表情、图片消息等完整界面
- **右键二级菜单圆角化**：整块面板圆角 + 仅悬停行圆角高亮

### 交互增强

- **聊天文本选择**：鼠标拖选任意已渲染文本，`Ctrl+C` 复制；与链接点击、滚动条拖动互不冲突
- **系统消息可复制**：选中后 `Ctrl+C` 复制；右键系统消息**只显示「复制」**（无引用）
- **聊天输入框**：鼠标拖选、`Ctrl+A` 全选、复制/粘贴/剪切、选中后删除
- **引用块双击跳转**：双击消息里的引用行 → 滚动到被引用原消息并短暂高亮
  - 本地引用精确记录目标；**其他玩家的引用**在服务端安装本模组时通过 hash 链路精确定位，否则退化为内容匹配
- **链接点击**正确打开系统浏览器
- **未知文本 ClickEvent 交回原版处理**（含 `Custom` / `ShowDialog`），插件自带的可点击菜单不再「点了没反应」

### 通知与免打扰

- **免打扰（DND）**
  - 左上角月亮按钮，**按会话独立**：公屏一份，每个私聊各自一份
  - 进入私聊视图时，同一按钮自动切换为「该私聊」的开关
  - 免打扰时：不弹灵动岛横幅、静默非 @ 提示音；**@提及 / 引用始终提醒**；**左下角红点保留**
  - 玩家列表中被免打扰的玩家显示**紫色月亮**，名字过长自动截断并加 `...`
- **公屏消息小窗**（聊天面板关闭时）
  - 左下角聊天图标上方显示最近 3 条公屏消息
  - 半透明圆角面板、发送者名与正文异色、长消息多行且不重复名字
  - 新消息从下往上顶、最旧淡出；**静默 5 秒**（设置中可调 1~60，热更新即时生效）后淡出
  - 公屏免打扰时不弹出；跟随 `F1` 隐藏
- **在线人数状态行**：侧边栏底部显示绿色 `【在线N人】`（含自己，与 TAB 口径一致）/ `【单人游戏中】`
- **玩家列表在线状态**：头像右下角小圆点（在线绿 / 离线灰）

### 兼容与稳定性

- **Jade（玉）软兼容**：显示 Jade 描述框时，通知横幅自动避让
- **皮肤解析异步化**：消除渲染线程阻塞造成的卡顿
- **主线程卡顿看门狗**：主线程长时间无 tick 时输出诊断日志
- **服务端联动通道按服务器白名单声明 + 自愈**（见下节）

---

## 服务端联动与网络（重要）

本模组在服务端安装时可以启用**消息元数据、精确引用跳转（含其他玩家的引用）、私聊增强、@提及同步**等联动功能。

### 为什么需要「白名单」

自定义载荷通道的声明发生在**配置阶段**，而那一刻客户端**无法得知服务端是否也装了本模组**（协议不下发模组列表）；同时，所有模组声明的通道会被合并成一张清单发给服务端，某些服务端（如 Paper）对这张清单的**数量**有硬上限。

因此采用「**按服务器记忆** + **按连接注册**」策略：

| 场景 | 是否声明本模组通道 | 联动 |
|---|---|---|
| 单机 / 自己开局域网 | ✅ 是 | 全部可用 |
| 局域网（私有地址） | ✅ 是 | 全部可用 |
| **已记住的 E33Chat 服务器** | ✅ 是 | 全部可用 |
| 其它远程服务器（Paper / 原版 / 未装本模组的服） | ❌ **否（0 个通道）** | 不可用（本地功能不受影响） |

关键点：

- **按连接注册/注销**：每次连接重新判定，断开即清理，不会把上一台服务器的通道残留带到下一台
- **自动记忆、自愈**：第 1 次进入某服务器后，若检测到服务端也装了本模组，就把地址写入 `config/e33chat/e33chat-servers.txt`；**第 2 次连接起**自动声明通道（首次连服受协议时序限制，无法当场启用）。服务端后来移除本模组，也会自动忘记该服务器
- 玩家**无需手动维护**任何文件
- 兜底开关：在 `config/e33chat/` 下放置空文件 `no-custom-channels.flag` 可**完全关闭**通道声明（联动随之不可用）

### Paper 服务端的「通道数量上限」（与白名单是两个问题）

Paper（及部分严格服务端）对客户端声明的**通道总数**有硬上限，超限时报：

```
Couldn't handle custom payload on channel minecraft:register
java.lang.IllegalStateException: Cannot register channel. Too many channel registered!
→ 客户端被踢：Invalid custom payload payload!
```

这通常是**整合包整体通道数超限**（模组越多越容易触发），本模组只占其中 8 个；由于非白名单服务器我们不声明任何通道，**本模组不会成为超限的推手**。

**服务端侧解决方案**（需要服主操作）：给服务端启动参数加上

```
-Dpaper.disableChannelLimit=true
```

### 引用同步是怎么工作的

- 客户端引用消息时发送 `quote_sync`（含消息 hash、被引用消息 hash、被引用者与内容）
- **服务端收到后立即广播** `chat_meta` 给所有在线玩家（不再依赖聊天事件，因此离线模式/未签名聊天同样有效）；以 `UUID|msgHash` 做 10 秒去重，避免与聊天事件兜底路径重复广播
- 其他客户端按消息 hash 匹配消息，匹配不上就先挂起（10 秒 TTL），等消息到达后自动挂上
- 一条消息即使先到达/后到达都能正确渲染引用

---

## 配置

- 配置文件：`config/e33chat/e33chat-client.json`
- 图形界面：Mod Menu → E33Chat → 配置界面（分类开关，含 HUD / @提醒 / 主题 / 气泡等）

部分新增配置项：

| 配置项 | 说明 |
|---|---|
| `themeAccent` / `themeCustom` | 强调色 / 自定义配色 |
| `dndEnabled` | 公屏免打扰 |
| `dndWhisperPlayers` | 已设为免打扰的私聊玩家名单 |
| `publicOverlayEnabled` | 公屏消息小窗开关 |
| `publicOverlayIdleSeconds` | 小窗静默淡出秒数（1~60） |
| `redDotEnabled` | 左下角聊天图标红点 |
| `mentionBannerEnabled` / `systemBannerEnabled` | 灵动岛横幅开关 |

---

## 构建

```bash
# 需要 JDK 25 与 Gradle 9.8.2（仓库内 gradlew 仍是 9.5.1，版本不够）
gradle build
```

产物：

| 文件 | 大小 |
|---|---|
| `build/libs/e33chat-Fabric-26.2-3.0.1.jar` | 543,103 B |
| `build/libs/e33chat-Fabric-26.2-3.0.1-sources.jar` | 353,562 B |

---

## paper-plugin（Paper 服务端插件）

`paper-plugin/` 是独立的 Gradle 子项目（不在根构建里），随本分支一同携带，产物为 **Paper 1.21.11** 服务端插件 **E33ChatPaper 3.0.1**：

| 项目 | 值 |
|---|---|
| 目标服务端 | Paper 1.21.11（未在 Paper 26.x 上验证） |
| 产物 | `paper-plugin/build/libs/【paper端】E33ChatPaper-3.0.1.jar` |
| 大小 | **15,028 B**（加入 @提及跨玩家同步前为 11,456 B） |
| 作用 | 引用同步（C2S `e33chat:quote_sync` → S2C `e33chat:chat_meta` 广播）+ @提及同步（`AsyncChatEvent` 解析 `@名字`） |

线格式与 Fabric 侧逐字节一致，客户端无需任何改动；字段表与构建方式见 [`paper-plugin/README.md`](paper-plugin/README.md)。

---

## 已知限制

- 本分支要求 **Java 25 + Fabric Loader 0.20.1 + Fabric API `0.161.0+26.2`**，版本不匹配会直接报 `java` / `minecraft` 依赖不满足；换到 **26.3** 请用 `mc26.3` 分支（Fabric API `0.165.0+26.3`）
- 构建需要 **Gradle 9.8+**（Loom 1.19.3），仓库内 `gradlew` 的 9.5.1 不够用
- **首次**连接某个 E33Chat 服务器时不会立即启用联动（协议时序限制），**重连一次**即可生效
- 纯插件服 / 原版服无法提供完整的私聊联动，客户端只能按文本格式推断
- 引用精确跳转：本地引用精确；他人引用在服务端安装本模组时精确，否则按内容就近匹配（内容完全相同的多条消息可能落到邻近那条）
- 公屏小窗只在聊天面板关闭时显示
- 免打扰的横幅/声音拦截仅作用于本模组的通知系统（不影响原版与其它模组）

---

## 相关文档

- [`CHANGELOG.md`](CHANGELOG.md) —— 完整变更历史
- [`PORT_NOTES.md`](PORT_NOTES.md) —— 移植笔记（版本差异、踩坑记录）
- [`README_EN.md`](README_EN.md) —— 英文说明
- [`paper-plugin/README.md`](paper-plugin/README.md) —— Paper 服务端插件（线格式 / 构建）
- 上游项目：[E33EPUS/E33Chat](https://github.com/E33EPUS/E33Chat)

---

## 致谢与许可

- 原作者：**E33EPUS**，遵循 MIT License（见 [`LICENSE`](LICENSE)）
- 本移植版为**非官方**版本，新增功能与修复由本仓库维护；如遇问题请先确认是否与本版增强相关
- 感谢整合包社区的使用反馈与测试

> 若你只想要上游的原始功能，建议使用原项目；本版适合希望获得更完整聊天体验的玩家。
