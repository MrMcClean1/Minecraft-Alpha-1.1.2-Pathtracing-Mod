package com.alexmcclellan.betapt;

import com.alexmcclellan.betapt.config.BetaPTConfig;
import com.alexmcclellan.betapt.render.PathTracer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Entry point for the Beta Path Tracer mod.
 *
 * Fabric calls onInitializeClient() once, early in startup, before the game window
 * (and its OpenGL context) exists. Anything that touches OpenGL has to wait until
 * the window is created, which is why the GPU check lives in MinecraftMixin instead.
 */
public class BetaPT implements ClientModInitializer {
    public static final String MOD_ID = "alphapt";
    public static final Logger LOGGER = LogManager.getLogger("BetaPT");
    public static String version = "dev";

    @Override
    public void onInitializeClient() {
        // the AI bots (off until /bot on in chat)
        com.alexmcclellan.betapt.bots.Bots.setup(com.alexmcclellan.betapt.bots.BotEntity.class, com.alexmcclellan.betapt.bots.BotEntity::new);
        version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        BetaPTConfig.load();
        PathTracer.INSTANCE.enabled = BetaPTConfig.startEnabled;
        com.alexmcclellan.betapt.config.PtSettings.load(); // look, brightness, denoiser from the in-game menu
        LOGGER.info("Beta Path Tracer {} loaded. Waiting for the OpenGL context.", version);
    }
}
