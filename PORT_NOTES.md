# E33Chat 移植到 1.21.11 说明

已将 E33Chat（Fabric 聊天气泡模组）从 `1.21.1 / yarn` 移植到 `1.21.11 / yarn`。
构建产物：`e33chat-Fabric-1.21.11-2.4.2.jar`（与 `project\build\libs\` 同源）。

## 目标版本（对齐你现在玩的 1.21.11K 配置）
| 项 | 1.21.1 原值 | 1.21.11 新值 |
|---|---|---|
| minecraft | 1.21.1 | 1.21.11 |
| yarn | 1.21.1+build.3 | 1.21.11+build.6 |
| fabric-loader | 0.19.3 | 0.19.3 |
| fabric-api | 0.116.13+1.21.1 | 0.141.5+1.21.11 |
| modmenu (compileOnly) | 11.0.3 | 17.0.0-beta.2 |
| loom | 1.7.4 | 1.17.19 |
| gradle | 8.8 | 9.5.1 |
| java | 21 | 21 |

## 构建命令
```powershell
$env:JAVA_HOME='C:\BianChengJA\JDK21'
$env:GRADLE_USER_HOME='C:\AI\ds harness\工作区\聊天mod移植1.21.11\.gradle-home'
$env:JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=<ASCII路径>\truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
cd project
.\gradlew.bat build -x test     # 跳过 test（沙箱限制：Gradle 测试 worker 的管道 stdio 被阻止）
```
- 脚本 `.\gradlew.bat build`（运行 test）在此环境因 `GradleWorkerMain ClassNotFound` 无法跑通，属沙箱限制；测试代码本身已通过编译。
- Maven Central 直连被墙，已改用 Aliyun 镜像（见 `build.gradle`/`settings.gradle`）。

## 主要 API 迁移点
- **输入系统**（1.21.9+重构）：`Screen.keyPressed(KeyInput)`、`mouseClicked(Click,boolean)`、`mouseDragged(Click,dx,dy)`、`mouseReleased(Click)`；`KeyInput`/`Click` record；`KeyBinding.matchesKey(KeyInput)`；`Screen.hasShiftDown()` 移除；`filesDragged`→`onFilesDropped`；`ChatScreen(String,boolean)`。
- **渲染**：`DrawContext.getMatrices()`→`org.joml.Matrix3x2fStack`（`pushMatrix()/popMatrix()/translate(x,y)/scale(x,y)`，2D 无 z）；`drawBorder`→`drawStrokedRectangle`；`draw()` 移除；`drawTexture/drawGuiTexture` 首参需 `RenderPipelines.GUI_TEXTURED`。
- **ClickEvent/HoverEvent 接口化**：`getValue()` 移除；`ClickEvent.SuggestCommand/RunCommand/OpenUrl/OpenFile/CopyToClipboard` 记录；`HoverEvent.ShowText` 等。
- **皮肤/资产**：`SkinTextures`→`net.minecraft.entity.player.SkinTextures`（record，`body()/cape()/elytra()`→`AssetInfo$TextureAsset.texturePath()`）；`PlayerSkinProvider.getSkinTextures(GameProfile)`→`supplySkinTextures(profile,false)`；`PlayerListEntry.getSkinTextures()`。
- **权限**：`hasPermissionLevel(int)` 移除→`getPermissions().hasPermission(new Permission.Level(PermissionLevel.X))`（OP2=`GAMEMASTERS`）。
- **服务器访问**：`Entity/ServerPlayerEntity.getServer()` 移除→`getEntityWorld().getServer()`；`ServerCommandSource.getServer()` 保留。
- **音频**：`PositionedSoundInstance.master()`→`ui()`/`ambient()`。
- **Text 序列化**：`Text.Serialization.*` 移除→`TextCodecs.CODEC`+`JsonOps`。
- **纹理**：`NativeImageBackedTexture` 构造改为 `(Supplier<String>, NativeImage)`；`registerDynamicTexture`→`registerTexture(Identifier, AbstractTexture)`。
- **Mixin 目标**：`ChatHud.render` 改为 7 参描述符 `render(DrawContext;TextRenderer;IIIZZ)V`；`MessageHandler.onChatMessage/onProfilelessMessage/onGameMessage` 签名保留。
- **GameProfile 记录化**：`getId()/getName()`→`id()/name()`。

## 已知限制（非崩溃）
- 圆角：1.21.11 的 RenderPipeline 系统给每气泡 `u_Rect/u_Radius` uniform 不便，`RoundRectRenderer` 已改为**运行时生成抗锯齿圆角掩码纹理 + `drawTexture(...,argb)` 颜色 tint** 实现（按 (w,h,radius) 缓存，异常回退 `g.fill` 直角）。圆角已恢复。
- 背景模糊：`GlStateManager` 移除，`BlurRenderer` 已改为调用原生 `DrawContext.applyBlur()`（带 try/catch，不崩溃）；若 DrawContext 状态不匹配则保持清晰（best-effort）。
- mixer 修复：`MouseHandlerAccessor` 的 `Mouse.activeButton` 在 1.21.11 类型变为 `MouseInput`(非 int)，已改 accessor 与调用点（设为 null 清除按下），否则运行时 mixin 注入会失败。
- 第三方 ChatImage 的 hover URL 提取做了最小适配（接口化后仅对 `ShowText` 取 `value()`，取不到时返回 null）。
