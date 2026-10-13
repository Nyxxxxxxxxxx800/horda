package com.horda;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Horda: NPCs (manequins) com skins variadas que nascem parados
 * e, quando voce manda, correm atras de voce. Uns so correm, outros correm e pulam.
 *
 * Comandos:
 *   /horda spawn <quantidade> [raio]
 *   /horda start
 *   /horda stop
 *   /horda clear
 */
public class HordaMod implements ModInitializer {

    // ===================== CONFIGURACAO (mude aqui) =====================

    /** Nicks cujas skins serao sorteadas. Troque/adicione os que quiser. */
    private static final String[] SKINS = {
            "Notch", "jeb_", "Dinnerbone", "Grumm", "Technoblade", "Dream",
            "GeorgeNotFound", "Sapnap", "Skeppy", "BadBoyHalo", "Philza",
            "TommyInnit", "Tubbo_", "Ranboo", "WilburSoot", "Quackity",
            "Fundy", "Punz", "Antfrost", "CaptainSparklez", "DanTDM", "Herobrine"
    };

    /** % de NPCs que correm E pulam. O resto so corre. */
    private static final int JUMPER_PERCENT = 40;

    /** Velocidade de corrida em blocos por tick (jogador correndo ~ 0.28). */
    private static final double MIN_SPEED = 0.30;
    private static final double MAX_SPEED = 0.42;

    /** Forca do pulo e frequencia (1 chance em N a cada tick) dos saltadores. */
    private static final double JUMP_POWER = 0.42;
    private static final int JUMP_CHANCE = 25;

    /** Distancia em que eles param de chegar perto de voce. */
    private static final double STOP_DISTANCE = 2.0;

    /** NPCs criados por tick (evita lag com hordas grandes). */
    private static final int SPAWNS_PER_TICK = 10;

    /** Raio de busca dos NPCs ao redor de voce. */
    private static final double SEARCH_RANGE = 250.0;

    // ====================================================================

    private static final String TAG = "horda";

    private record Pending(ServerLevel level, String command) {}

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final Map<UUID, Double> VERTICAL = new HashMap<>();
    private static boolean running = false;
    private static UUID targetId = null;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(HordaMod::onTick);
    }

    // ============================ COMANDOS ============================

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("horda")
                .then(Commands.literal("spawn")
                        .then(Commands.argument("quantidade", IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> spawn(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "quantidade"), 25))
                                .then(Commands.argument("raio", IntegerArgumentType.integer(3, 200))
                                        .executes(ctx -> spawn(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "quantidade"),
                                                IntegerArgumentType.getInteger(ctx, "raio"))))))
                .then(Commands.literal("start")
                        .executes(ctx -> start(ctx.getSource())))
                .then(Commands.literal("stop")
                        .executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("clear")
                        .executes(ctx -> clear(ctx.getSource()))));
    }

    private static int spawn(CommandSourceStack src, int amount, int radius) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        clearAll(src.getServer());

        Random rnd = new Random();
        double cx = player.getX();
        double cz = player.getZ();

        for (int i = 0; i < amount; i++) {
            double angle = rnd.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.4 + 0.6 * rnd.nextDouble());
            double x = cx + Math.cos(angle) * dist;
            double z = cz + Math.sin(angle) * dist;

            // altura do chao; se for estranha (caverna/nether), usa a altura do jogador
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            double y = (ground > player.getBlockY() + 15 || ground < player.getBlockY() - 30)
                    ? player.getY() : ground;

            // olhando para o centro
            float yaw = (float) (Math.atan2(cz - z, cx - x) * 180.0 / Math.PI) - 90.0f;
            String skin = SKINS[rnd.nextInt(SKINS.length)];

            String cmd = String.format(Locale.ROOT,
                    "summon minecraft:mannequin %.2f %.2f %.2f "
                            + "{Tags:[\"%s\"],Invulnerable:1b,Rotation:[%.1ff,0.0f],hide_description:1b,"
                            + "profile:{name:\"%s\"}}",
                    x, y, z, TAG, yaw, skin);
            PENDING.add(new Pending(level, cmd));
        }

        src.sendSuccess(() -> Component.literal("Criando " + amount + " NPCs... depois use /horda start"), false);
        return amount;
    }

    private static int start(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        targetId = player.getUUID();
        running = true;
        VERTICAL.clear();
        src.sendSuccess(() -> Component.literal("A horda esta correndo atras de voce!"), false);
        return 1;
    }

    private static int stop(CommandSourceStack src) {
        running = false;
        VERTICAL.clear();
        src.sendSuccess(() -> Component.literal("Horda parada."), false);
        return 1;
    }

    private static int clear(CommandSourceStack src) {
        clearAll(src.getServer());
        src.sendSuccess(() -> Component.literal("Horda removida."), false);
        return 1;
    }

    private static void clearAll(MinecraftServer server) {
        PENDING.clear();
        running = false;
        VERTICAL.clear();
        for (ServerLevel level : server.getAllLevels()) {
            runCommand(server, level, "kill @e[tag=" + TAG + "]");
        }
    }

    private static void runCommand(MinecraftServer server, ServerLevel level, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }

    // ============================== TICK ==============================

    private static void onTick(MinecraftServer server) {
        // 1) cria os NPCs aos poucos
        for (int i = 0; i < SPAWNS_PER_TICK && !PENDING.isEmpty(); i++) {
            Pending p = PENDING.poll();
            runCommand(server, p.level(), p.command());
        }

        // 2) movimento da horda
        if (!running) return;

        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null) {
            running = false;
            VERTICAL.clear();
            return;
        }

        Level level = target.level();
        List<Entity> horde = level.getEntities(
                (Entity) null,
                target.getBoundingBox().inflate(SEARCH_RANGE),
                e -> e.isAlive() && e.getTags().contains(TAG));

        for (Entity e : horde) {
            double dx = target.getX() - e.getX();
            double dz = target.getZ() - e.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            // caracteristicas fixas de cada NPC, derivadas do UUID
            long h = e.getUUID().getLeastSignificantBits();
            h ^= (h >>> 33);
            boolean jumper = Math.floorMod(h, 100L) < JUMPER_PERCENT;
            double speed = MIN_SPEED + (Math.floorMod(h >>> 7, 1000L) / 999.0) * (MAX_SPEED - MIN_SPEED);

            double mx = 0;
            double mz = 0;
            boolean chasing = dist > STOP_DISTANCE;
            if (chasing) {
                mx = dx / dist * speed;
                mz = dz / dist * speed;

                float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                e.setYRot(yaw);
                if (e instanceof LivingEntity living) {
                    living.setYBodyRot(yaw);
                    living.setYHeadRot(yaw);
                }
            }
            e.setSprinting(chasing);

            // gravidade e pulo
            double vy = VERTICAL.getOrDefault(e.getUUID(), 0.0);
            if (e.onGround()) {
                vy = -0.08; // mantem encostado no chao
                boolean blocked = chasing && e.horizontalCollision;
                boolean randomJump = chasing && jumper && ThreadLocalRandom.current().nextInt(JUMP_CHANCE) == 0;
                if (blocked || randomJump) {
                    vy = JUMP_POWER;
                }
            } else {
                vy = (vy - 0.08) * 0.98;
            }

            e.setDeltaMovement(Vec3.ZERO);
            e.move(MoverType.SELF, new Vec3(mx, vy, mz));
            VERTICAL.put(e.getUUID(), vy);
        }
    }
}
