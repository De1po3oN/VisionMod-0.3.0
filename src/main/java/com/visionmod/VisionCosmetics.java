package com.visionmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class VisionCosmetics {
    // The verified Cloudflare Worker address. Never include admin credentials in the mod.
    private static final String SERVER =
        "https://rough-truth-7b7dvision-cosmetics.vision-mod.workers.dev";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4)).build();
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "VisionCosmetics-network");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static int ticks;

    public static String localHead = "none";
    public static String localBack = "none";

    private record Entry(String head, String back, long fetchedAt) {}

    private VisionCosmetics() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                CACHE.clear(); ticks=0; return;
            }
            if (!VisionModClient.cosmetics || !VisionModClient.showOtherCosmetics) return;
            if (++ticks % 600 != 0) return;
            long now = System.currentTimeMillis();
            for (AbstractClientPlayer player : client.level.players()) {
                if (player == client.player) continue;
                UUID uuid = player.getUUID();
                Entry old = CACHE.get(uuid);
                if (old != null && now-old.fetchedAt < 25_000L) continue;
                CACHE.put(uuid,new Entry("none","none",now));
                NETWORK.execute(() -> fetch(uuid));
            }
        });

        WorldRenderEvents.BEFORE_ENTITIES.register(context -> {
            Minecraft client=Minecraft.getInstance();
            ClientLevel level=client.level;
            if (level==null || client.player==null || !VisionModClient.enabled
                || !VisionModClient.cosmetics || context.consumers()==null) return;
            Vec3 camera=client.gameRenderer.getMainCamera().position();
            VertexConsumer lines=context.consumers().getBuffer(RenderTypes.lines());
            for(AbstractClientPlayer player:level.players()) {
                if(player.distanceToSqr(client.player)>48.0*48.0) continue;
                String head,back;
                if(player==client.player) {
                    head=localHead;back=localBack;
                } else {
                    if(!VisionModClient.showOtherCosmetics) continue;
                    Entry e=CACHE.get(player.getUUID());
                    if(e==null) continue;
                    head=e.head;back=e.back;
                }
                double x=player.getX()-camera.x;
                double y=player.getY()-camera.y;
                double z=player.getZ()-camera.z;
                // Initial built-in wireframe cosmetic prototypes. These are NOT custom 3D files.
                if("ears".equals(head)) {
                    outline(context,lines,x-.31,y+1.78,z-.12,.12,.27,.22,0xFFFF78CB);
                    outline(context,lines,x+.19,y+1.78,z-.12,.12,.27,.22,0xFFFF78CB);
                } else if("crown".equals(head)) {
                    outline(context,lines,x-.33,y+1.89,z-.33,.66,.16,.66,0xFFFFD166);
                }
                if("wings".equals(back)) {
                    outline(context,lines,x-.82,y+.65,z+.25,.58,.95,.12,0xFF67E8F9);
                    outline(context,lines,x+.24,y+.65,z+.25,.58,.95,.12,0xFF67E8F9);
                } else if("pack".equals(back)) {
                    outline(context,lines,x-.28,y+.60,z+.22,.56,.77,.28,0xFF65F0B2);
                }
            }
        });
    }

    private static void outline(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext ctx,
            VertexConsumer lines,double x,double y,double z,
            double w,double h,double d,int color) {
        ShapeRenderer.renderShape(ctx.matrices(),lines,Shapes.box(0,0,0,w,h,d),x,y,z,color,2.0F);
    }

    private static String name(JsonObject data,String key) {
        if(!data.has(key) || data.get(key).isJsonNull() || !data.get(key).isJsonPrimitive())
            return "none";
        String value=data.get(key).getAsString();
        return switch(value) {
            case "ears", "crown", "wings", "pack" -> value;
            default -> "none";
        };
    }

    private static void fetch(UUID uuid) {
        try {
            HttpRequest req=HttpRequest.newBuilder(
                URI.create(SERVER+"/cosmetics/"+uuid.toString()))
                .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response=HTTP.send(req,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>4096) return;
            JsonObject body=JsonParser.parseString(response.body()).getAsJsonObject();
            if(!body.has("cosmetics") || !body.get("cosmetics").isJsonObject()) return;
            JsonObject cosmetics=body.getAsJsonObject("cosmetics");
            CACHE.put(uuid,new Entry(name(cosmetics,"head"),name(cosmetics,"back"),System.currentTimeMillis()));
        } catch(Exception ignored) {
            // Cosmetics must never make Minecraft crash if the service is offline.
        }
    }
}
