package com.remotecompanion.agent;

import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 极简本地 HTTP 接口：只绑 127.0.0.1，给 QQ 侧调用。 */
public class Api {
    private static final Pattern NUM = Pattern.compile("\"%s\"\\s*:\\s*(-?[0-9.]+)");
    private static final Pattern STR = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    public static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = readBody(ex);
        if (path.equals("/skin.png")) {
            byte[] png = AgentMod.skinPng();
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(png); }
            return;
        }
        String result;
        try {
            result = route(path, method, body);
        } catch (Exception e) {
            result = "{\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
        byte[] out = result.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    private static String route(String path, String method, String body) {
        switch (path) {
            case "/status":
                return AgentMod.posJson();
            case "/spawn": {
                double x = num(body, "x", 0), y = num(body, "y", 64), z = num(body, "z", 0);
                AgentMod.spawn(x, y, z);
                return "{\"ok\":true}";
            }
            case "/player":
                AgentMod.control(str(body, "name", "Companion"));
                return "{\"ok\":true}";
            case "/setskin":
                AgentMod.setSkin();
                return "{\"ok\":true}";
            case "/follow":
                AgentMod.follow(str(body, "name", "Super_Halley"));
                return "{\"ok\":true}";
            case "/goto": {
                AgentMod.gotoPos(num(body, "x", 0), num(body, "y", 64), num(body, "z", 0));
                return "{\"ok\":true}";
            }
            case "/say":
                AgentMod.say(str(body, "text", ""));
                return "{\"ok\":true}";
            case "/look":
                AgentMod.look((float) num(body, "yaw", 0), (float) num(body, "pitch", 0));
                return "{\"ok\":true}";
            case "/cmd":
                AgentMod.runCommand(str(body, "command", ""));
                return "{\"ok\":true}";
            case "/fly":
                AgentMod.setFly(str(body, "on", "true") != "false");
                return "{\"ok\":true}";
            case "/stop":
                AgentMod.stopMove();
                return "{\"ok\":true}";
            default:
                return "{\"error\":\"unknown path\",\"paths\":[\"/status\",\"/spawn\",\"/follow\",\"/goto\",\"/say\",\"/look\",\"/stop\"]}";
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static double num(String json, String key, double def) {
        try {
            Matcher m = Pattern.compile(NUM.pattern().replace("%s", key)).matcher(json);
            return m.find() ? Double.parseDouble(m.group(1)) : def;
        } catch (Exception e) { return def; }
    }

    private static String str(String json, String key, String def) {
        try {
            Matcher m = Pattern.compile(STR.pattern().replace("%s", key)).matcher(json);
            return m.find() ? m.group(1) : def;
        } catch (Exception e) { return def; }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
