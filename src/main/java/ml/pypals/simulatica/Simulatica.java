package ml.pypals.simulatica;

import fi.dy.masa.litematica.event.KeyCallbacks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Simulatica implements ModInitializer {
	public static final String MOD_ID = "simulatica";
	/** 模组版本号（从 fabric.mod.json 读，配置界面标题要显示）。 */
	public static final String VERSION = FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);


	private static void quietenLitematicaDevLogging() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		try {
			Configurator.setLevel("litematica", Level.WARN);
		} catch (Throwable t) {
			LOGGER.debug("Could not raise Litematica's log level", t);
		}
	}

	@Override
	public void onInitialize() {
		quietenLitematicaDevLogging();

		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Hello Fabric world!");
	}
}
