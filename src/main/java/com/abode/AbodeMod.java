package com.abode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class AbodeMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("abode");
    private final HashMap<UUID, PlayerData> playerDataMap = new HashMap<>();
    private MinecraftServer server;
    private Path configDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onInitialize() {
        LOGGER.info("Hello Fabric world!");

        configDir = FabricLoader.getInstance().getConfigDir().resolve("abode");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create config directory", e);
        }

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            this.server = server;
            loadPlayerData();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> savePlayerData());

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(CommandManager.literal("sethome")
                .then(CommandManager.argument("name", StringArgumentType.string())
                .executes(this::setHome)));

            dispatcher.register(CommandManager.literal("home")
                .then(CommandManager.argument("name", StringArgumentType.string())
                .executes(this::goHome)));

            dispatcher.register(CommandManager.literal("delhome")
                .then(CommandManager.argument("name", StringArgumentType.string())
                .executes(this::delHome)));

            dispatcher.register(CommandManager.literal("listhomes")
                .executes(this::listHomes));

            dispatcher.register(CommandManager.literal("setcooldown")
                .then(CommandManager.argument("minutes", IntegerArgumentType.integer(0))
                .executes(this::setCooldown)));
        });
    }

    private int setHome(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        String name = StringArgumentType.getString(context, "name");

        if (player != null) {
            BlockPos currentPos = player.getBlockPos();
            PlayerData playerData = getPlayerData(player.getUuid());
            playerData.homePositions.put(name, currentPos);
            playerDataMap.put(player.getUuid(), playerData);
            savePlayerData(); // Save immediately
            player.sendMessage(Text.of("Home position '" + name + "' set at " + currentPos.toShortString() + "!"), false);
            LOGGER.info("Set home '{}' for player {} at {}", name, player.getName().getString(), currentPos);
        }

        return 1; // Command executed successfully
    }

    private int goHome(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        String name = StringArgumentType.getString(context, "name");

        if (player != null) {
            PlayerData playerData = getPlayerData(player.getUuid());
            BlockPos homePos = playerData.homePositions.get(name);
            if (homePos == null) {
                player.sendMessage(Text.of("No home position set with the name '" + name + "'. Use /sethome <name> first."), false);
                return 1; // Command executed successfully
            }

            long currentTime = System.currentTimeMillis();
            if (playerData.cooldownEndTime > currentTime) {
                long timeLeft = playerData.cooldownEndTime - currentTime;
                long minutesLeft = TimeUnit.MILLISECONDS.toMinutes(timeLeft);
                long secondsLeft = TimeUnit.MILLISECONDS.toSeconds(timeLeft) - TimeUnit.MINUTES.toSeconds(minutesLeft);
                if (minutesLeft > 0) {
                    player.sendMessage(Text.of("You must wait " + minutesLeft + " minutes and " + secondsLeft + " seconds before using /home again."), false);
                } else {
                    player.sendMessage(Text.of("You must wait " + secondsLeft + " seconds before using /home again."), false);
                }
                return 1; // Command executed successfully
            }

            player.teleport(homePos.getX(), homePos.getY(), homePos.getZ());
            player.sendMessage(Text.of("Teleported to home '" + name + "' at " + homePos.toShortString() + "!"), false);
            LOGGER.info("Teleported player {} to home '{}' at {}", player.getName().getString(), name, homePos);

            if (playerData.cooldownMinutes > 0) {
                playerData.cooldownEndTime = currentTime + (playerData.cooldownMinutes * 60000);
                playerDataMap.put(player.getUuid(), playerData);
                savePlayerData(); // Save immediately
            }
        }

        return 1; // Command executed successfully
    }

    private int delHome(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        String name = StringArgumentType.getString(context, "name");

        if (player != null) {
            PlayerData playerData = getPlayerData(player.getUuid());
            if (playerData.homePositions.remove(name) != null) {
                player.sendMessage(Text.of("Home position '" + name + "' deleted."), false);
                savePlayerData(); // Save immediately
                LOGGER.info("Deleted home '{}' for player {}", name, player.getName().getString());
            } else {
                player.sendMessage(Text.of("No home position set with the name '" + name + "'."), false);
            }
        }

        return 1; // Command executed successfully
    }

    private int listHomes(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player != null) {
            PlayerData playerData = getPlayerData(player.getUuid());
            if (playerData.homePositions.isEmpty()) {
                player.sendMessage(Text.of("No home positions set."), false);
            } else {
                player.sendMessage(Text.of("Home positions: " + String.join(", ", playerData.homePositions.keySet())), false);
            }
        }

        return 1; // Command executed successfully
    }

    private int setCooldown(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player != null) {
            int minutes = IntegerArgumentType.getInteger(context, "minutes");
            PlayerData playerData = getPlayerData(player.getUuid());
            playerData.cooldownMinutes = minutes;
            long currentTime = System.currentTimeMillis();
            playerData.cooldownEndTime = currentTime + (minutes * 60000); // Reset cooldown timer
            playerDataMap.put(player.getUuid(), playerData);
            savePlayerData(); // Save immediately
            player.sendMessage(Text.of("Cooldown set to " + minutes + " minutes!"), false);
            LOGGER.info("Set cooldown for player {} to {} minutes", player.getName().getString(), minutes);
        }

        return 1; // Command executed successfully
    }

    private PlayerData getPlayerData(UUID uuid) {
        return playerDataMap.getOrDefault(uuid, new PlayerData());
    }

    private void loadPlayerData() {
        File dataFile = configDir.resolve("playerData.json").toFile();
        if (dataFile.exists()) {
            try (Reader reader = new FileReader(dataFile)) {
                PlayerDataContainer container = gson.fromJson(reader, PlayerDataContainer.class);
                playerDataMap.putAll(container.playerData);
                LOGGER.info("Loaded player data");
            } catch (IOException e) {
                LOGGER.error("Failed to load player data", e);
            }
        }
    }

    private void savePlayerData() {
        File dataFile = configDir.resolve("playerData.json").toFile();
        try (Writer writer = new FileWriter(dataFile)) {
            PlayerDataContainer container = new PlayerDataContainer();
            container.playerData.putAll(playerDataMap);
            gson.toJson(container, writer);
            LOGGER.info("Saved player data");
        } catch (IOException e) {
            LOGGER.error("Failed to save player data", e);
        }
    }

    private static class PlayerDataContainer {
        HashMap<UUID, PlayerData> playerData = new HashMap<>();
    }

    private static class PlayerData implements Serializable {
        HashMap<String, BlockPos> homePositions = new HashMap<>();
        int cooldownMinutes = 0;
        long cooldownEndTime = 0;
    }
}
