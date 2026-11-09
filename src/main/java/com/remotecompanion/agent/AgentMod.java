package com.remotecompanion.agent;

import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import com.mojang.authlib.properties.Property;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 伙伴的"身体"遥控器：驱动真实玩家（第二客户端）或盔甲架，本地 HTTP 控制。
 */
@Mod("remotecompanion")
public class AgentMod {
    public static final String MOD_ID = "remotecompanion";

    private static MinecraftServer server;
    private static ServerLevel level;
    private static ArmorStand body;
    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static volatile String statusCache = "{\"inWorld\":false,\"lastCmd\":\"\"}";
    private static volatile String lastResult = "";

    private static String followName = null;
    private static String controlName = null;
    private static Vec3 gotoPos = null;
    private static final float SPEED = 0.34f;
    private static byte[] skinCache = null;
    private static int stuckTicks = 0;
    private static volatile boolean forceFly = false;

    private HttpServer http;

    public AgentMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 3232), 0);
            http.createContext("/", exchange -> Api.handle(exchange));
            http.start();
        } catch (Exception e) {
            System.out.println("[xiaofeiyu] HTTP start failed: " + e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (http != null) http.stop(0);
        body = null;
        followName = null;
        controlName = null;
        gotoPos = null;
        TASKS.clear();
        statusCache = "{\"inWorld\":false,\"lastCmd\":\"\"}";
    }

    private static void submit(Runnable r) { TASKS.add(r); }

    public static void spawn(double x, double y, double z) {
        submit(() -> {
            if (server == null) return;
            try {
                level = server.overworld();
                double px = x, py = y, pz = z;
                if (x == 0 && y == 0 && z == 0) {
                    var first = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
                    if (first != null) { px = first.getX() + 2.0; py = first.getY(); pz = first.getZ(); }
                }
                if (body == null || body.isRemoved()) {
                    body = new ArmorStand(EntityType.ARMOR_STAND, level);
                    body.setCustomName(Component.literal("伙伴"));
                    body.setCustomNameVisible(true);
                    body.setNoGravity(false);
                    body.setInvulnerable(true);
                    body.setSilent(true);
                    body.setItemSlot(EquipmentSlot.HEAD, headItem());
                    body.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0xF5F2EA));
                    body.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0x2B2B33));
                    body.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, 0x2B2B33));
                    level.addFreshEntity(body);
                }
                body.setPos(px, py, pz);
                body.setDeltaMovement(0, 0, 0);
            } catch (Throwable t) {
                lastResult = "spawnErr:" + t;
            }
        });
    }

    public static void control(String name) {
        submit(() -> { controlName = name; });
    }

    public static void follow(String name) {
        submit(() -> { followName = name; gotoPos = null; });
    }

    public static void gotoPos(double x, double y, double z) {
        submit(() -> { gotoPos = new Vec3(x, y, z); followName = null; });
    }

    public static void stopMove() {
        submit(() -> { followName = null; gotoPos = null; });
    }

    public static void say(final String text) {
        submit(() -> {
            if (server == null) return;
            String safe = text == null ? "" : text.replace("\n", " ").trim();
            if (safe.isEmpty()) return;
            server.getPlayerList().broadcastSystemMessage(Component.literal("<伙伴> " + safe), false);
        });
    }

    public static void look(final float yaw, final float pitch) {
        submit(() -> {
            if (body == null) return;
            body.setYRot(yaw);
            body.setXRot(pitch);
        });
    }

    public static void runCommand(final String cmd) {
        submit(() -> {
            if (server == null || cmd == null) return;
            try {
                int r = server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                lastResult = "ok:" + r;
            } catch (Throwable t) {
                lastResult = "err:" + String.valueOf(t.getMessage());
            }
        });
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String[] TRIGGERS = {"companion", "小肥鱼", "小肥魚", "伙伴"};

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        String msg = event.getMessage().getString();
        lastResult = "see:" + msg;
        if (msg == null) return;
        if (msg.startsWith("/")) return;
        boolean hit = false;
        for (String k : TRIGGERS) {
            if (msg.contains(k)) { hit = true; break; }
        }
        // 她在 10 格内时，带“你/?/？”的话也算在跟她说话
        if (!hit) {
            ServerPlayer cp = controlName == null ? null : server.getPlayerList().getPlayerByName(controlName);
            if (cp != null) {
                ServerPlayer sp = event.getPlayer();
                double dist = Math.hypot(cp.getX() - sp.getX(), cp.getZ() - sp.getZ());
                if (dist < 10.0 && (msg.contains("你") || msg.contains("?") || msg.contains("？"))) hit = true;
            }
        }
        if (!hit) return;
        final String from = event.getPlayer().getName().getString();
        final String text = msg;
        lastResult = "chat:" + from + ":" + text;
        relayForward(from, text);
    }

    private static boolean relayForward(String from, String text) {
        try {
            String body = "{\"from\":\"" + from.replace("\"", "'") + "\",\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "'") + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:3233/chat"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static byte[] skinPng() {
        if (skinCache == null) {
            try {
                skinCache = Files.readAllBytes(Paths.get("config/remotecompanion/skin.png"));
            } catch (Throwable t) {
                skinCache = new byte[0];
            }
        }
        return skinCache;
    }

    public static void setSkin() {
        submit(() -> {
            if (server == null || controlName == null) return;
            ServerPlayer cp = server.getPlayerList().getPlayerByName(controlName);
            if (cp == null) { lastResult = "skinErr:no player"; return; }
            try {
                String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://127.0.0.1:3232/skin.png\"}}}";
                String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
                cp.getGameProfile().getProperties().put("textures", new Property("textures", b64));
                server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, cp));
                lastResult = "skinOk";
            } catch (Throwable t) {
                lastResult = "skinErr:" + t;
            }
        });
    }

    public static String posJson() { return statusCache; }

    private static ItemStack dyed(Item item, int color) {
        ItemStack s = new ItemStack(item);
        CompoundTag tag = s.getOrCreateTag();
        CompoundTag display = tag.getCompound("display");
        display.putInt("color", color);
        tag.put("display", display);
        return s;
    }

    private static ItemStack headItem() {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = s.getOrCreateTag();
        CompoundTag owner = new CompoundTag();
        owner.putString("Name", "Companion");
        tag.put("SkullOwner", owner);
        return s;
    }

    private static void updateStatus() {
        ServerPlayer cp = controlName == null ? null : server.getPlayerList().getPlayerByName(controlName);
        if (cp != null) {
            Vec3 p = cp.position();
            statusCache = String.format("{\"mode\":\"player\",\"name\":\"%s\",\"inWorld\":true,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"following\":%s,\"lastCmd\":\"%s\"}",
                    controlName, p.x, p.y, p.z, followName == null ? "null" : "\"" + followName + "\"",
                    lastResult.replace("\"", "'"));
            return;
        }
        ArmorStand b = body;
        if (b == null || b.isRemoved()) {
            statusCache = "{\"mode\":\"none\",\"inWorld\":false,\"lastCmd\":\"" + lastResult.replace("\"", "'") + "\"}";
            return;
        }
        Vec3 p = b.position();
        statusCache = String.format("{\"mode\":\"stand\",\"inWorld\":true,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yaw\":%.1f,\"following\":%s,\"lastCmd\":\"%s\"}",
                p.x, p.y, p.z, b.getYRot(), followName == null ? "null" : "\"" + followName + "\"",
                lastResult.replace("\"", "'"));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Runnable r;
        while ((r = TASKS.poll()) != null) {
            try { r.run(); } catch (Throwable t) { lastResult = "taskErr:" + t; }
        }
        if (server == null) { updateStatus(); return; }

        if (controlName != null && followName != null) {
            ServerPlayer cp = server.getPlayerList().getPlayerByName(controlName);
            ServerPlayer op = server.getPlayerList().getPlayerByName(followName);
            if (cp != null && op != null) {
                Vec3 cpos = cp.position();
                double d = Math.hypot(op.getX() - cpos.x, op.getZ() - cpos.z);
                if (d > 2.2) {
                    double step = Math.min(SPEED, d - 1.8);
                    double nx = cpos.x + (op.getX() - cpos.x) / d * step;
                    double nz = cpos.z + (op.getZ() - cpos.z) / d * step;
                    float yaw = (float) Math.toDegrees(Math.atan2(op.getZ() - cpos.z, op.getX() - cpos.x)) - 90.0f;
                    boolean moved = tryTeleportPlayer(cp, nx, nz, yaw);
                    if (!moved) moved = tryTeleportPlayer(cp, cpos.x + (op.getX() - cpos.x) / d * step * 0.5, cpos.z + (op.getZ() - cpos.z) / d * step * 0.5, yaw);
                    if (!moved) {
                        stuckTicks++;
                        if (stuckTicks > 12) {
                            flyTo(cp, op, yaw);
                            stuckTicks = 0;
                        }
                    } else {
                        stuckTicks = 0;
                        if (cp.getAbilities().flying && d < 6.0) {
                            cp.getAbilities().flying = false;
                            cp.onUpdateAbilities();
                        }
                    }
                } else {
                    stuckTicks = 0;
                    if (cp.getAbilities().flying) {
                        cp.getAbilities().flying = false;
                        cp.onUpdateAbilities();
                    }
                }
            }
        }

        if (body == null || body.isRemoved()) { updateStatus(); return; }

        Vec3 target = null;
        if (gotoPos != null) target = gotoPos;
        else if (followName != null) {
            ServerPlayer p = server.getPlayerList().getPlayerByName(followName);
            if (p != null) target = p.position();
        }
        if (target != null) {
            Vec3 pos = body.position();
            double dist = Math.hypot(target.x - pos.x, target.z - pos.z);
            if (dist < 1.8) {
                if (gotoPos != null) gotoPos = null;
            } else {
                moveToward((target.x - pos.x) / dist, (target.z - pos.z) / dist, dist);
            }
        }
        updateStatus();
    }

    private static double groundY(ServerPlayer p, double nx, double nz, double fromY) {
        try {
            Vec3 from = new Vec3(nx, fromY + 2.0, nz);
            Vec3 to = new Vec3(nx, fromY - 8.0, nz);
            ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p);
            BlockHitResult hit = p.serverLevel().clip(ctx);
            if (hit.getType() == HitResult.Type.BLOCK) {
                return hit.getBlockPos().getY() + 1.0;
            }
        } catch (Throwable ignored) {
        }
        return fromY;
    }

    public static void setFly(final boolean on) {
        submit(() -> {
            forceFly = on;
            if (server == null || controlName == null) return;
            ServerPlayer cp = server.getPlayerList().getPlayerByName(controlName);
            if (cp == null) return;
            cp.getAbilities().mayfly = true;
            cp.getAbilities().flying = on;
            cp.onUpdateAbilities();
        });
    }
    private static void flyTo(ServerPlayer cp, ServerPlayer op, float yaw) {
        try {
            cp.getAbilities().mayfly = true;
            cp.getAbilities().flying = true;
            cp.onUpdateAbilities();
            cp.connection.teleport(op.getX(), op.getY() + 2.0, op.getZ(), yaw, cp.getXRot());
        } catch (Throwable ignored) {
        }
    }
    private static boolean tryTeleportPlayer(ServerPlayer p, double nx, double nz, float yaw) {
        double ox = p.getX(), oy = p.getY(), oz = p.getZ();
        double base = groundY(p, nx, nz, oy);
        double[] dys = {0.0, 1.0, -1.0, 2.0, -2.0, 3.0, -3.0};
        for (double dy : dys) {
            double ny = base + dy;
            AABB box = p.getBoundingBox().move(nx - ox, ny - oy, nz - oz);
            if (!p.serverLevel().noCollision(p, box)) continue;
            p.connection.teleport(nx, ny, nz, yaw, p.getXRot());
            return true;
        }
        return false;
    }
    private static void moveToward(double dx, double dz, double dist) {
        Vec3 pos = body.position();
        double step = Math.min(SPEED, Math.max(0.05, dist - 1.6));
        double nx = pos.x + dx * step;
        double nz = pos.z + dz * step;

        if (tryMove(nx, pos.y, nz)) return;
        for (double up = 0.6; up <= 1.6; up += 0.5) {
            if (tryMove(nx, pos.y + up, nz)) return;
        }
        double sx = -dz * 0.7, sz = dx * 0.7;
        tryMove(pos.x + sx * step, pos.y, pos.z + sz * step);
    }

    private static boolean tryMove(double x, double y, double z) {
        double ox = body.getX(), oy = body.getY(), oz = body.getZ();
        AABB box = body.getBoundingBox().move(x - ox, y - oy, z - oz);
        if (!level.noCollision(body, box)) return false;
        float yaw = (float) Math.toDegrees(Math.atan2(z - oz, x - ox)) - 90.0f;
        body.setPos(x, y, z);
        body.setYRot(yaw);
        return true;
    }
}
