<img src="icon.png" width="128" alt="Remote Companion">

# Remote Companion

给 **任意 AI 角色**一个 Minecraft 里的身体：模组在本机开一个 HTTP 接口，AI 侧（本地 LLM、OpenAI、角色扮演框架、脚本）通过它让角色**走、看、说、跟随、飞行、执行服务器指令**。

面向的典型场景：你在写一个"陪玩"的 AI 角色，希望它真的出现在你的 MC 世界里（而不是只在聊天窗口里），并且**完全可控、默认安全**。

## 它做什么

- **绑定身体**：把一个真实玩家（离线小号）或一个盔甲架绑定成角色的"身体"；
- **跟随/传送**：`follow`（带碰撞检测、贴地、卡住自动飞越）、`goto`、`spawn`；
- **说话**：`say` 在公屏以角色名义发言；
- **看**：`look` 设置朝向；
- **飞行**：`fly on/off` 让身体保持创造飞行；
- **执行指令**：`cmd` 在服务器执行任意指令（**默认关闭**，见下）；
- **聊天接入**：可选地把游戏内聊天转发给本地 relay（`127.0.0.1:3233`），由你的 AI 回复；
- **自动开 LAN**（客户端侧）：进入单人世界后自动开放局域网（默认端口 25565），省去手点。

## 安装

1. 需要 **Minecraft 1.20.1 + Forge 47.x**（构建用 47.3.0 验证）；
2. 把 `remotecompanion-<version>.jar` 放进 `mods/`；
3. 启动游戏，进入世界（单人世界会自动开 LAN）。

## 接口

只监听 `127.0.0.1:3232`，JSON 收发：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/status` | 返回当前身体状态（`mode`: `player`/`stand`/`none`）、坐标、跟随对象、`lastCmd` |
| POST | `/player` | `{"name":"<玩家名>"}` 绑定一个真实玩家作为身体 |
| POST | `/spawn` | `{"x":..,"y":..,"z":..}` 生成盔甲架身体（传 0,0,0 则生成在首个玩家身旁） |
| POST | `/follow` | `{"name":"<玩家名>"}` 让身体跟随该玩家 |
| POST | `/goto` | `{"x":..,"y":..,"z":..}` 走到坐标（仅盔甲架身体） |
| POST | `/stop` | 停止移动 |
| POST | `/say` | `{"text":"..."}` 公屏发言（`<角色名> 文本`） |
| POST | `/look` | `{"yaw":..,"pitch":..}` 设置盔甲架朝向 |
| POST | `/fly` | `{"on":true|false}` 真实玩家保持飞行 |
| POST | `/setskin` | 给绑定玩家写本地皮肤（配合 CustomSkinLoader） |
| POST | `/cmd` | `{"command":"..."}` 执行服务器指令（**默认关闭**） |
| GET | `/skin.png` | 返回 `config/remotecompanion/skin.png`（若存在） |

示例：

```bash
curl -s http://127.0.0.1:3232/status
curl -s -X POST http://127.0.0.1:3232/player -H 'content-type: application/json' -d '{"name":"Companion"}'
curl -s -X POST http://127.0.0.1:3232/follow -H 'content-type: application/json' -d '{"name":"YourName"}'
curl -s -X POST http://127.0.0.1:3232/say    -H 'content-type: application/json' -d '{"text":"我来了"}'
```

更多示例见 `examples/`。

## 安全

- 只绑定 `127.0.0.1`，**不要**把端口暴露到公网；
- `/cmd` 能执行任意服务器指令，默认关闭；需要时在 `config/remotecompanion.cfg`（或自行加开关）显式打开；
- 聊天转发只在消息命中触发词、或身体在你 10 格内且消息含“你/？”时才发生，避免刷 token。

## 皮肤（离线号）

原版离线玩家只能用默认皮肤。推荐客户端装 **CustomSkinLoader**，把皮肤放到：

```
<gameDir>/CustomSkinLoader/LocalSkin/skins/<玩家名>.png
```

并把 `CustomSkinLoader.json` 的 `loadlist` 里 `{"name":"LocalSkin","type":"Legacy"}` **挪到第一位**（默认排在最后，会被前面的皮肤站抢先返回默认皮肤）。

## 从源码构建

```bash
# 需要 JDK 17
./gradlew build
# 产物：build/libs/remotecompanion-<version>.jar
```

（ForgeGradle 6 需要访问 `maven.minecraftforge.net`。）

## 许可

MIT，见 `LICENSE`。
