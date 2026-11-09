# BedrockSecureChat

让 Geyser 基岩版玩家在 `server.properties` 保持 `enforce-secure-profile=true` 的情况下正常发送普通聊天消息。Java 玩家仍然使用原版签名聊天，不会由本插件拦截或重写。

## 支持环境

- Paper / Purpur 26.2
- Java 25
- Geyser-Spigot 2.11.x（提供 Geyser API）
- PacketEvents 2.13.0 或更新版本

本插件使用 Geyser API 判断玩家是否来自 Bedrock 连接，不要求必须安装 Floodgate；但如果你的 Geyser 身份验证方案本身需要 Floodgate，仍需按 Geyser 的要求安装 Floodgate。

## 工作方式

1. PacketEvents 监听客户端发来的普通聊天数据包。
2. 通过 Geyser API 判断该连接是否来自 Bedrock。
3. 对 Bedrock 玩家，将消息重新交给 Paper 的 `AsyncChatEvent`。
4. 使用 Paper 事件的 renderer 和 viewer 列表发送消息，因此常见的聊天格式、接收者、过滤、禁言插件可以继续处理该事件。
5. 原始网络数据包保持原样继续进入服务器，不取消、不改写，避免破坏聊天确认状态。

本插件不会伪造 Mojang 签名。基岩版转发消息属于未签名的系统消息，因此不能通过 Mojang 的聊天举报系统验证；Java 玩家原本的签名消息不受影响。

## 安装

1. 确认服务器使用 Paper/Purpur 26.2，启动 Java 25。
2. 安装并正常启用 PacketEvents（2.13.0 或更新版本）。
3. 安装并正常启用 Geyser-Spigot。
4. 将构建生成的 `BedrockSecureChat-1.0.0.jar` 放入 `plugins/`。
5. 保持 `server.properties` 中 `enforce-secure-profile=true`。
6. 完整重启服务器。

首次启动后会生成 `plugins/BedrockSecureChat/config.yml`。

## 配置

```yaml
enabled: true
max-message-length: 256
cooldown-ms: 0
debug: false
```

- `enabled`：是否启用转发。
- `max-message-length`：普通聊天消息的最大长度。
- `cooldown-ms`：同一基岩版玩家两条转发消息之间的最小间隔，默认关闭。
- `debug`：输出额外调试日志。

修改配置后需要重启服务器；当前版本没有热重载命令。

## 构建

需要 JDK 25 和 Gradle 9.1.0。

```bash
gradle clean build
```

Windows PowerShell 同样可以执行：

```powershell
gradle clean build
```

构建产物：`build/libs/BedrockSecureChat-1.0.0.jar`。

GitHub Actions 会在推送到 main/master、提交 Pull Request 或手动触发时自动构建，并将 JAR 作为 Actions Artifact 上传。

## 当前版本范围

当前版本处理的是**普通公开聊天消息**。以 `/` 开头的命令走 Minecraft 独立的聊天命令数据包，不会被本版本转发；普通命令仍由服务器原有命令系统处理。插件也不会改变聊天签名策略、服务器 `server.properties` 或 Java 玩家的消息包。

## 故障排查

- 插件无法加载：检查 PacketEvents 和 Geyser-Spigot 是否已正确安装并启用。
- 基岩玩家仍无法聊天：检查服务端是否确实加载了本插件；打开 `debug: true` 后重启并检查日志。
- 控制台可能仍出现 `Failed to update secure chat state for <玩家>`：这是原版服务器拒绝无签名消息时产生的提示。插件不会取消原始包，因为保留它是为了让服务器继续处理聊天确认信息；转发后的消息仍会正常显示。

## 许可证

MIT License
