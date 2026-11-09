package com.remotecompanion.agent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 单人世界进图后自动开放局域网（端口 25565，允许作弊），省掉手点。
 */
@Mod.EventBusSubscriber(modid = "remotecompanion", value = Dist.CLIENT)
public class AutoLan {
    public static final int LAN_PORT = 25565;
    private static boolean tried = false;
    private static int delay = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) { tried = false; delay = 0; return; }
        if (tried) return;
        delay++;
        if (delay < 60) return; // 等世界稳定 3 秒
        try {
            IntegratedServer server = mc.getSingleplayerServer();
            if (server != null && !server.isPublished()) {
                boolean ok = server.publishServer(GameType.SURVIVAL, true, LAN_PORT);
                System.out.println("[xiaofeiyu] auto LAN publish -> " + ok + " port=" + LAN_PORT);
            }
            tried = true;
        } catch (Throwable t) {
            System.out.println("[xiaofeiyu] auto LAN 失败: " + t);
            tried = true;
        }
    }
}
