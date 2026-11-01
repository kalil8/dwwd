package com.gaspoweredcake.client33.systems.accounts;

import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.mojang.authlib.services.response.discovery.Discovery;
import com.mojang.authlib.services.response.discovery.DiscoveryResponse;
import com.mojang.authlib.services.response.discovery.Endpoint;
import com.mojang.authlib.services.response.discovery.Endpoints;

import java.net.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Maps the existing third-party account endpoints to Authlib 10 discovery. */
public final class LegacyAccountServices {
    public static final String AUTHENTICATION = "http://authserver.thealtening.com";
    private static final String SESSION = "http://sessionserver.thealtening.com/session/minecraft/";
    private static final String PROFILES = "https://api.mojang.com/minecraft/profile/lookup/";

    private LegacyAccountServices() {}

    public static MinecraftServicesDiscoveryService create(Proxy proxy) {
        Discovery discovery = new Discovery("minecraft",
            new Endpoints(Map.of("getPublicKeys", new Endpoint(AUTHENTICATION + "/publickeys"))),
            new Endpoints(Map.of(
                "join", new Endpoint(SESSION + "join"),
                "verify", new Endpoint(SESSION + "hasJoined"),
                "getProfileById", new Endpoint(SESSION + "profile/{profileId}"))),
            Endpoints.empty(),
            new Endpoints(Map.of(
                "getManyByName", new Endpoint(PROFILES + "bulk/byname"),
                "getByName", new Endpoint(PROFILES + "name/{name}"),
                "getTexture", new Endpoint("https://textures.minecraft.net/texture/{textureId}",
                    List.of("https://textures.minecraft.net/texture/{textureId}", "http://textures.minecraft.net/texture/{textureId}")))),
            Endpoints.empty());
        Supplier<DiscoveryResponse> response = () -> new DiscoveryResponse("The Altening", "minecraft", discovery);
        try {
            // Authlib exposes custom discovery URLs, but no public factory for a fixed
            // legacy endpoint table. Keep this version-specific bridge in one place.
            var constructor = MinecraftServicesDiscoveryService.class.getDeclaredConstructor(Proxy.class, boolean.class, Supplier.class);
            if (!constructor.trySetAccessible()) throw new IllegalStateException("Authlib discovery constructor is inaccessible");
            return constructor.newInstance(proxy, true, response);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unable to adapt legacy account endpoints to Authlib 10", error);
        }
    }
}
