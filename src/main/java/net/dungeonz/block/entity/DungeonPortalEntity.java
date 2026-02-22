package net.dungeonz.block.entity;

import net.dungeonz.block.DungeonPortalBlock;
import net.dungeonz.block.screen.DungeonPortalScreenHandler;
import net.dungeonz.compat.LootrCompat;
import net.dungeonz.dungeon.Dungeon;
import net.dungeonz.dungeon.DungeonDataManager;
import net.dungeonz.dungeon.DungeonPlacementHandler;
import net.dungeonz.dungeon.DungeonRuntimeData;
import net.dungeonz.init.*;
import net.dungeonz.network.DungeonServerPacket;
import net.dungeonz.network.packet.DungeonPortalPacket;
import net.dungeonz.util.DungeonHelper;
import net.dungeonz.util.InventoryHelper;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.EndPortalBlockEntity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import org.joml.Vector3f;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.RegistryWrapper.WrapperLookup;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.Map.Entry;

public class DungeonPortalEntity extends EndPortalBlockEntity implements ExtendedScreenHandlerFactory<DungeonPortalPacket> {

    private static final Logger LOGGER = LogManager.getLogger("DungeonPortal");

    private Text title = Text.translatable("container.dungeon_portal");
    private String dungeonType = "";
    private String difficulty = "";
    private boolean dungeonStructureGenerated = false;
    private List<UUID> dungeonPlayerUuids = new ArrayList<UUID>();

    /**
     * When non-null, overrides the default origin calculation in
     * {@link #startDungeonTeleportCountdown} and
     * {@link net.dungeonz.dungeon.DungeonPlacementHandler#enter}.
     * Set by MythicDungeons before triggering the countdown to redirect
     * structure generation to an instanced slot.
     * Cleared after each use so non-Mythic+ runs are unaffected.
     */
    @Nullable
    private BlockPos instanceOrigin = null;

    /**
     * Optional callback fired at the very start of
     * {@link #startDungeonTeleportCountdown} — before the origin is resolved
     * and before any generation happens.
     *
     * MythicDungeons registers here to create the RunInstance and call
     * {@link #setInstanceOrigin} at exactly the right moment.
     * Set to {@code null} (default) to disable.
     */
    @Nullable
    public static java.util.function.Consumer<DungeonPortalEntity> onCountdownStart = null;

    @Nullable
    public BlockPos getInstanceOrigin() {
        return instanceOrigin;
    }

    public void setInstanceOrigin(@Nullable BlockPos pos) {
        this.instanceOrigin = pos;
    }

    /** Returns the origin to use for generation/teleportation — slot origin if set, portal-based default otherwise. */
    public BlockPos resolveOrigin() {
        if (instanceOrigin != null) return instanceOrigin;
        return new BlockPos(0, 0, 0).add(this.getPos().getX() * 16, 100, this.getPos().getZ() * 16);
    }
    private List<UUID> deadDungeonPlayerUuids = new ArrayList<UUID>();
    private int maxGroupSize = 0;
    private int minGroupSize = 0;
    private List<UUID> waitingUuids = new ArrayList<UUID>();
    private int cooldownTime = 0;
    private int autoKickTime = 0;
    private boolean privateGroup = false;
    // Large runtime data (blockBlockPosMap, movingBlockMap, etc.) is now stored via DungeonDataManager
    // to avoid NBT size limits. See DungeonDataManager and DungeonRuntimeData classes.
    private BlockPos bossBlockPos = new BlockPos(0, 0, 0);
    private BlockPos bossLootBlockPos = new BlockPos(0, 0, 0);
    private int dungeonTeleportCountdown = 0;
    private boolean needsMigration = false;
    private NbtCompound pendingMigrationData = null;
    private boolean validationChecked = false;

    public DungeonPortalEntity(BlockPos pos, BlockState state) {
        super(BlockInit.DUNGEON_PORTAL_ENTITY, pos, state);
    }

    protected DungeonPortalEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        this.dungeonType = nbt.getString("DungeonType");
        this.difficulty = nbt.getString("Difficulty");
        this.dungeonStructureGenerated = nbt.getBoolean("DungeonStructureGenerated");
        this.dungeonPlayerUuids.clear();
        for (int i = 0; i < nbt.getInt("DungeonPlayerCount"); i++) {
            this.dungeonPlayerUuids.add(nbt.getUuid("PlayerUUID" + i));
        }
        this.deadDungeonPlayerUuids.clear();
        for (int i = 0; i < nbt.getInt("DeadDungeonPlayerCount"); i++) {
            this.deadDungeonPlayerUuids.add(nbt.getUuid("DeadPlayerUUID" + i));
        }
        this.maxGroupSize = nbt.getInt("MaxGroupSize");
        this.minGroupSize = nbt.getInt("MinGroupSize");
        this.cooldownTime = nbt.getInt("CooldownTime");
        this.autoKickTime = nbt.getInt("AutoKickTime");
        this.privateGroup = nbt.getBoolean("PrivateGroup");

        int[] bossPos = nbt.getIntArray("BossPos");
        if (bossPos.length > 0) {
            this.bossBlockPos = new BlockPos(bossPos[0], bossPos[1], bossPos[2]);
        }
        int[] bossLootPos = nbt.getIntArray("BossLootPos");
        if (bossLootPos.length > 0) {
            this.bossLootBlockPos = new BlockPos(bossLootPos[0], bossLootPos[1], bossLootPos[2]);
        }

        // MIGRATION: Check if this is old format (has large runtime data in NBT)
        boolean isOldFormat = nbt.contains("BlockMapSize") || nbt.contains("MovingPosSize") ||
                              nbt.contains("ChestListSize") || nbt.contains("DungeonEdgeSize");

        if (isOldFormat) {
            // World is null during initial load, so defer migration to first server tick
            this.needsMigration = true;
            this.pendingMigrationData = nbt.copy();
            LOGGER.info("Detected old NBT format for dungeon portal at {}. Migration will occur on first tick.", this.pos);
        }
    }

    /**
     * Performs migration from old NBT format to new DungeonDataManager system.
     * Called during first server tick when old format is detected.
     */
    private void performMigration(ServerWorld world) {
        NbtCompound nbt = this.pendingMigrationData;
        if (nbt == null) {
            return;
        }

        LOGGER.info("Performing deferred migration for dungeon portal at {}...", this.pos);

        DungeonRuntimeData runtimeData = new DungeonRuntimeData();

        // Migrate blockBlockPosMap
        if (nbt.getInt("BlockMapSize") > 0) {
            HashMap<Integer, ArrayList<BlockPos>> tempBlockMap = new HashMap<>();
            for (int i = 0; i < nbt.getInt("BlockMapSize"); i++) {
                ArrayList<BlockPos> posList = new ArrayList<>();
                for (int u = 0; u < nbt.getInt("BlockListSize" + i); u++) {
                    int[] blockPos = nbt.getIntArray("BlockPos" + i + "" + u);
                    posList.add(new BlockPos(blockPos[0], blockPos[1], blockPos[2]));
                }
                tempBlockMap.put(nbt.getInt("BlockId" + i), posList);
            }
            runtimeData.setBlockBlockPosMap(tempBlockMap);
        }

        // Migrate chest list
        if (nbt.getInt("ChestListSize") > 0) {
            List<BlockPos> tempChestList = new ArrayList<>();
            for (int i = 0; i < nbt.getInt("ChestListSize"); i++) {
                int[] chestPos = nbt.getIntArray("ChestPos" + i);
                tempChestList.add(new BlockPos(chestPos[0], chestPos[1], chestPos[2]));
            }
            runtimeData.setChestPosList(tempChestList);
        }

        // Migrate exit list
        if (nbt.getInt("ExitListSize") > 0) {
            List<BlockPos> tempExitList = new ArrayList<>();
            for (int i = 0; i < nbt.getInt("ExitListSize"); i++) {
                int[] exitPos = nbt.getIntArray("ExitPos" + i);
                tempExitList.add(new BlockPos(exitPos[0], exitPos[1], exitPos[2]));
            }
            runtimeData.setExitPosList(tempExitList);
        }

        // Migrate gate list
        if (nbt.getInt("GateListSize") > 0) {
            List<BlockPos> tempGateList = new ArrayList<>();
            for (int i = 0; i < nbt.getInt("GateListSize"); i++) {
                int[] gatePos = nbt.getIntArray("GatePos" + i);
                tempGateList.add(new BlockPos(gatePos[0], gatePos[1], gatePos[2]));
            }
            runtimeData.setGatePosList(tempGateList);
        }

        // Migrate spawner map
        if (nbt.getInt("SpawnerMapSize") > 0) {
            HashMap<BlockPos, String> tempSpawnerMap = new HashMap<>();
            for (int i = 0; i < nbt.getInt("SpawnerListSize"); i++) {
                int[] spawnerPos = nbt.getIntArray("SpawnerPos" + i);
                String entityId = nbt.getString("SpawnerEntityId" + i);
                tempSpawnerMap.put(new BlockPos(spawnerPos[0], spawnerPos[1], spawnerPos[2]), entityId);
            }
            runtimeData.setSpawnerPosEntityIdMap(tempSpawnerMap);
        }

        // Migrate replace map
        if (nbt.getInt("ReplacePosSize") > 0) {
            HashMap<BlockPos, Integer> tempReplaceMap = new HashMap<>();
            for (int i = 0; i < nbt.getInt("ReplacePosSize"); i++) {
                int[] replacePos = nbt.getIntArray("ReplacePos" + i);
                tempReplaceMap.put(new BlockPos(replacePos[0], replacePos[1], replacePos[2]), replacePos[3]);
            }
            runtimeData.setReplacePosBlockIdMap(tempReplaceMap);
        }

        // Migrate moving block map
        if (nbt.getInt("MovingPosSize") > 0) {
            Map<BlockPos, Integer> tempMovingMap = new HashMap<>();
            for (int i = 0; i < nbt.getInt("MovingPosSize"); i++) {
                int[] movingPos = nbt.getIntArray("MovingPos" + i);
                tempMovingMap.put(new BlockPos(movingPos[0], movingPos[1], movingPos[2]), movingPos[3]);
            }
            runtimeData.setMovingBlockMap(tempMovingMap);
        }

        // Migrate powered block map
        if (nbt.getInt("PoweredPosSize") > 0) {
            Map<BlockPos, Powered> tempPoweredMap = new HashMap<>();
            for (int i = 0; i < nbt.getInt("PoweredPosSize"); i++) {
                int[] poweredPos = nbt.getIntArray("PoweredPos" + i);
                if (poweredPos.length >= 7) {
                    boolean isPowered = poweredPos[4] == 1;
                    tempPoweredMap.put(new BlockPos(poweredPos[0], poweredPos[1], poweredPos[2]), new Powered(poweredPos[3], isPowered, poweredPos[5], poweredPos[6]));
                }
            }
            runtimeData.setPoweredBlockMap(tempPoweredMap);
        }

        // Migrate dungeon edge list
        if (nbt.getInt("DungeonEdgeSize") > 0) {
            List<Integer> tempEdgeList = new ArrayList<>();
            for (int i = 0; i < nbt.getInt("DungeonEdgeSize") / 3; i++) {
                int[] dungeonEdgePos = nbt.getIntArray("DungeonEdge" + i);
                tempEdgeList.add(dungeonEdgePos[0]);
                tempEdgeList.add(dungeonEdgePos[1]);
                tempEdgeList.add(dungeonEdgePos[2]);
            }
            runtimeData.setDungeonEdgeList(tempEdgeList);
        }

        // Save migrated data to new system
        DungeonDataManager.migrateFromOldNbt(world, this.pos, runtimeData);

        LOGGER.info("Migration complete for dungeon portal at {}. Data moved to separate file.", this.pos);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);

        // Basic metadata
        nbt.putString("DungeonType", this.dungeonType);
        nbt.putString("Difficulty", this.difficulty);
        nbt.putBoolean("DungeonStructureGenerated", this.dungeonStructureGenerated);
        nbt.putInt("DungeonPlayerCount", this.dungeonPlayerUuids.size());
        for (int i = 0; i < this.dungeonPlayerUuids.size(); i++) {
            nbt.putUuid("PlayerUUID" + i, this.dungeonPlayerUuids.get(i));
        }
        nbt.putInt("DeadDungeonPlayerCount", this.deadDungeonPlayerUuids.size());
        for (int i = 0; i < this.deadDungeonPlayerUuids.size(); i++) {
            nbt.putUuid("DeadPlayerUUID" + i, this.deadDungeonPlayerUuids.get(i));
        }
        nbt.putInt("MaxGroupSize", this.maxGroupSize);
        nbt.putInt("MinGroupSize", this.minGroupSize);
        nbt.putInt("CooldownTime", this.cooldownTime);
        nbt.putInt("AutoKickTime", this.autoKickTime);
        nbt.putBoolean("PrivateGroup", this.privateGroup);

        // Boss positions (small, always needed)
        nbt.putIntArray("BossPos", List.of(this.bossBlockPos.getX(), this.bossBlockPos.getY(), this.bossBlockPos.getZ()));
        nbt.putIntArray("BossLootPos", List.of(this.bossLootBlockPos.getX(), this.bossLootBlockPos.getY(), this.bossLootBlockPos.getZ()));

        // If migration from old format hasn't completed yet, preserve old data in NBT
        // to prevent data loss if chunk is saved before first serverTick
        if (this.needsMigration && this.pendingMigrationData != null) {
            for (String key : this.pendingMigrationData.getKeys()) {
                if (!nbt.contains(key)) {
                    nbt.put(key, this.pendingMigrationData.get(key).copy());
                }
            }
            LOGGER.debug("Preserved old NBT format for dungeon portal at {} (migration pending)", this.pos);
        } else {
            LOGGER.debug("Saved minimal NBT for dungeon portal at {} (new system)", this.pos);
        }
    }

    public static void clientTick(World world, BlockPos pos, BlockState state, DungeonPortalEntity blockEntity) {
        // For multi-block portals, secondary blocks carry no data — always read from the main entity
        DungeonPortalEntity source = blockEntity;
        if (DungeonPortalBlock.isOtherPortalBlockNearby(world, pos, state.getBlock())) {
            DungeonPortalEntity main = DungeonPortalBlock.getMainPortalEntity(world, pos, state.getBlock());
            if (main != null) {
                source = main;
            }
        }

        if (source.dungeonType.isEmpty()) {
            return;
        }

        // Spawn ~1 particle every 3 ticks
        if (world.getRandom().nextInt(2) != 0) {
            return;
        }

        Vector3f color;
        if (source.getDungeonPlayerCount() > 0) {
            color = new Vector3f(0.2f, 0.4f, 1.0f);   // blue  — active
        } else if (source.isOnCooldown((int) world.getTime())) {
            color = new Vector3f(1.0f, 0.2f, 0.2f);   // red   — on cooldown
        } else {
            color = new Vector3f(0.2f, 1.0f, 0.3f);   // green — ready
        }

        for (int i = 0; i < 1; i++) {
            double bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
            double x, y, z, vx = 0, vy = 0, vz = 0;
            // Spawn on a random face surface so particles aren't hidden inside the block
            switch (world.getRandom().nextInt(6)) {
                case 0 -> { x = bx + world.getRandom().nextDouble(); y = by + 1.01; z = bz + world.getRandom().nextDouble(); vy =  0.04; } // top
                case 1 -> { x = bx + world.getRandom().nextDouble(); y = by - 0.01; z = bz + world.getRandom().nextDouble(); vy = -0.04; } // bottom
                case 2 -> { x = bx + 1.01; y = by + world.getRandom().nextDouble(); z = bz + world.getRandom().nextDouble(); vx =  0.04; } // east
                case 3 -> { x = bx - 0.01; y = by + world.getRandom().nextDouble(); z = bz + world.getRandom().nextDouble(); vx = -0.04; } // west
                case 4 -> { x = bx + world.getRandom().nextDouble(); y = by + world.getRandom().nextDouble(); z = bz + 1.01; vz =  0.04; } // south
                default -> { x = bx + world.getRandom().nextDouble(); y = by + world.getRandom().nextDouble(); z = bz - 0.01; vz = -0.04; } // north
            }
            world.addParticle(new DustParticleEffect(color, 1.2f), x, y, z, vx, vy, vz);
        }
    }

    public static void serverTick(World world, BlockPos pos, BlockState state, DungeonPortalEntity blockEntity) {
        // MIGRATION: Perform deferred migration from old NBT format on first tick
        if (blockEntity.needsMigration && blockEntity.pendingMigrationData != null && world instanceof ServerWorld serverWorld) {
            blockEntity.performMigration(serverWorld);
            blockEntity.needsMigration = false;
            blockEntity.pendingMigrationData = null;
        }

        // Validate and repair portal data once after load
        if (!blockEntity.validationChecked && !blockEntity.dungeonType.isEmpty()) {
            blockEntity.validationChecked = true;
            Dungeon dungeon = blockEntity.getDungeon();
            if (dungeon != null) {
                boolean repaired = false;
                if (blockEntity.difficulty.isEmpty() || !dungeon.getDifficultyList().contains(blockEntity.difficulty)) {
                    blockEntity.setDifficulty(dungeon.getDifficultyList().get(0));
                    repaired = true;
                }
                if (blockEntity.maxGroupSize != dungeon.getMaxGroupSize()) {
                    blockEntity.setMaxGroupSize(dungeon.getMaxGroupSize());
                    repaired = true;
                }
                if (blockEntity.minGroupSize != dungeon.getMinGroupSize()) {
                    blockEntity.setMinGroupSize(dungeon.getMinGroupSize());
                    repaired = true;
                }
                if (repaired) {
                    LOGGER.info("Repaired dungeon portal '{}' at {}", blockEntity.dungeonType, pos);
                    blockEntity.markDirty();
                }
            }
        }

        if (blockEntity.getDungeonPlayerCount() > 0) {
            if (blockEntity.autoKickTime == 0) {
                blockEntity.autoKickTime = (int) world.getTime() + 432000;
            } else if (blockEntity.autoKickTime < (int) world.getTime()) {
                if (blockEntity.getDungeon() != null) {
                    blockEntity.setCooldownTime(blockEntity.getDungeon().getCooldown() + (int) blockEntity.getWorld().getTime());
                    for (int i = 0; i < blockEntity.getDungeonPlayerUuids().size(); i++) {
                        ServerPlayerEntity player = (ServerPlayerEntity) world.getPlayerByUuid(blockEntity.getDungeonPlayerUuids().get(i));
                        if (DungeonHelper.getCurrentDungeon(player) != null) {
                            DungeonHelper.teleportOutOfDungeon(player);
                            player.sendMessage(Text.translatable("text.dungeonz.dungeon_autokick"));
                        }
                    }
                }
                blockEntity.getDungeonPlayerUuids().clear();
                blockEntity.getDeadDungeonPlayerUUIDs().clear();
                blockEntity.autoKickTime = 0;
                world.updateListeners(pos, state, state, Block.NOTIFY_ALL);
            }
        } else if (blockEntity.autoKickTime != 0) {
            blockEntity.autoKickTime = 0;
        }
        if (blockEntity.dungeonTeleportCountdown >= 1) {
            if (blockEntity.dungeonTeleportCountdown % 20 == 0) {
                for (int i = 0; i < blockEntity.getWaitingUuids().size(); i++) {
                    if (((ServerWorld) blockEntity.getWorld()).getEntity(blockEntity.getWaitingUuids().get(i)) != null
                            && ((ServerWorld) blockEntity.getWorld()).getEntity(blockEntity.getWaitingUuids().get(i)) instanceof ServerPlayerEntity serverPlayerEntity) {
                        DungeonServerPacket.writeS2CDungeonTeleportCountdown(serverPlayerEntity, blockEntity.dungeonTeleportCountdown);
                    }
                }

            }
            blockEntity.dungeonTeleportCountdown--;

            if (blockEntity.dungeonTeleportCountdown == (ConfigInit.CONFIG.defaultDungeonTeleportCountdown / 2)) {
                DungeonPlacementHandler.refreshDungeon(((ServerWorld) blockEntity.getWorld()).getServer(), blockEntity.getWorld().getServer().getWorld(DimensionInit.DUNGEON_WORLD), blockEntity,
                        blockEntity.getDungeon(), blockEntity.getDifficulty());
            }

            if (blockEntity.dungeonTeleportCountdown == 0) {
                for (int i = 0; i < blockEntity.getWaitingUuids().size(); i++) {
                    if (((ServerWorld) blockEntity.getWorld()).getEntity(blockEntity.getWaitingUuids().get(i)) != null
                            && ((ServerWorld) blockEntity.getWorld()).getEntity(blockEntity.getWaitingUuids().get(i)) instanceof ServerPlayerEntity serverPlayerEntity) {
                        DungeonHelper.teleportPlayer(serverPlayerEntity, blockEntity.getWorld().getServer().getWorld(DimensionInit.DUNGEON_WORLD), blockEntity, blockEntity.getPos());
                    }
                }
                blockEntity.getWaitingUuids().clear();
            }
        }
    }

    @Override
    public Text getDisplayName() {
        if (this.getDungeon() != null) {
            return Text.translatable("dungeon." + this.getDungeonType());
        }
        return title;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(WrapperLookup registryLookup) {
        return this.createNbt(registryLookup);
    }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity playerEntity) {
        return new DungeonPortalScreenHandler(syncId, playerInventory, this, ScreenHandlerContext.create(world, pos));
    }

    @Override
    public boolean shouldDrawSide(Direction direction) {
        return true;
    }

    @Override
    public DungeonPortalPacket getScreenOpeningData(ServerPlayerEntity player) {
        List<String> difficulties = new ArrayList<String>();
        Map<String, List<ItemStack>> possibleLoot = new HashMap<>();
        Map<String, List<ItemStack>> requiredItemStacks = new HashMap<>();
        Optional<Identifier> backgroundId = Optional.empty();

        int requiredLevel = 0;
        boolean allowRespawn = false;
        boolean keepInventory = false;
        boolean allowPositiveEffects = false;
        boolean allowEnderPearl = false;
        boolean allowWindCharge = false;
        boolean allowElytra = false;
        boolean allowMobsLoot = true;
        boolean allowBossLoot = true;
        if (this.getDungeon() instanceof Dungeon dungeon) {
            difficulties = dungeon.getDifficultyList();
            possibleLoot = DungeonHelper.getPossibleLootItemStackMap(dungeon, player.getServer());
            requiredItemStacks = DungeonHelper.getRequiredItemStackList(dungeon);
            backgroundId = Optional.ofNullable(dungeon.getBackgroundId());
            requiredLevel = dungeon.getRequiredLevel();
            allowEnderPearl = dungeon.isEnderPearlAllowed();
            allowWindCharge = dungeon.isWindChargeAllowed();
            allowPositiveEffects = dungeon.isPositiveEffectsAllowed();
            allowRespawn = dungeon.isRespawnAllowed();
            keepInventory = dungeon.isKeepInventory();
            allowElytra = dungeon.isElytraAllowed();
            allowMobsLoot = dungeon.isMobsLootAllowed();
            allowBossLoot = dungeon.isBossLootAllowed();
        }

        return new DungeonPortalPacket(this.getDungeonType(), this.pos, this.getDungeonPlayerUuids(), this.getDeadDungeonPlayerUUIDs(), difficulties, possibleLoot, requiredItemStacks, this.getMaxGroupSize(),
                this.getMinGroupSize(), this.getWaitingUuids().size(), requiredLevel, this.getCooldownTime(), this.getDifficulty(), allowEnderPearl, allowWindCharge, allowPositiveEffects, allowElytra, allowRespawn, keepInventory,
                allowMobsLoot, allowBossLoot, this.getPrivateGroup(), backgroundId);
    }

    public void finishDungeon(ServerWorld world, BlockPos pos) {
        List<PlayerEntity> players = world.getPlayers(TargetPredicate.createAttackable().setBaseMaxDistance(64.0), null, new Box(pos).expand(64.0, 64.0, 64.0));
        for (PlayerEntity player : players) {
            CriteriaInit.DUNGEON_COMPLETION.trigger((ServerPlayerEntity) player, this.getDungeonType(), this.getDifficulty());
            player.sendMessage(
                Text.translatable("text.dungeonz.dungeon_completion")
                    .formatted(Formatting.GOLD),
                    false
            );
            player.sendMessage(
                Text.translatable("text.dungeonz.dungeon_leave")
                            .styled(style -> style
                            .withColor(Formatting.GREEN)
                            .withUnderline(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dungeon leave"))
                ),
                false
            );
        }
        world.playSound(null, pos, SoundInit.DUNGEON_COMPLETION_EVENT, SoundCategory.BLOCKS, 1.0f, 0.9f + world.getRandom().nextFloat() * 0.2f);

        for (int i = 0; i < this.getExitPosList().size(); i++) {
            world.setBlockState(this.getExitPosList().get(i), BlockInit.DUNGEON_PORTAL.getDefaultState(), 3);
        }

        String bossLootTableString = this.getDungeon().getDifficultyBossLootTableMap().get(this.getDifficulty());
        if (ConfigInit.CONFIG.lootrIntegration && LootrCompat.isLootrAvailable()) {
            world.setBlockState(this.getBossLootBlockPos(), Blocks.CHEST.getDefaultState(), 3);
            LootrCompat.convertToLootrChest(world, this.getBossLootBlockPos(), bossLootTableString);
        } else {
            world.setBlockState(this.getBossLootBlockPos(), Blocks.CHEST.getDefaultState(), 3);
            InventoryHelper.fillInventoryWithLoot(world.getServer(), world, this.getBossLootBlockPos(), bossLootTableString);
        }

        this.setCooldownTime(this.getDungeon().getCooldown() + (int) this.getWorld().getTime());
        markDirty();
    }

    @Nullable
    public Dungeon getDungeon() {
        return Dungeon.getDungeon(this.dungeonType);
    }

    public void setDungeonType(String dungeonType) {
        this.dungeonType = dungeonType;
        this.markDirty();
        if (this.world != null && !this.world.isClient()) {
            this.world.updateListeners(this.pos, this.getCachedState(), this.getCachedState(), Block.NOTIFY_ALL);
        }
    }

    public String getDungeonType() {
        return this.dungeonType;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getDifficulty() {
        return this.difficulty;
    }

    public void setDungeonStructureGenerated() {
        this.dungeonStructureGenerated = true;
    }

    public boolean isDungeonStructureGenerated() {
        return this.dungeonStructureGenerated;
    }

    public void joinDungeon(UUID playerUuid) {
        if (!this.dungeonPlayerUuids.contains(playerUuid)) {
            this.dungeonPlayerUuids.add(playerUuid);
            this.markDirty();
            if (this.world != null && !this.world.isClient()) {
                this.world.updateListeners(this.pos, this.getCachedState(), this.getCachedState(), Block.NOTIFY_ALL);
            }
        }
    }

    public void leaveDungeon(UUID playerUuid) {
        this.dungeonPlayerUuids.remove(playerUuid);
        this.markDirty();
        if (this.world != null && !this.world.isClient()) {
            this.world.updateListeners(this.pos, this.getCachedState(), this.getCachedState(), Block.NOTIFY_ALL);
        }
    }

    public int getDungeonPlayerCount() {
        return this.dungeonPlayerUuids.size();
    }

    public void setDungeonPlayerUuids(List<UUID> dungeonPlayerUuids) {
        this.dungeonPlayerUuids = dungeonPlayerUuids;
    }

    public List<UUID> getDungeonPlayerUuids() {
        return this.dungeonPlayerUuids;
    }

    public void addDeadDungeonPlayerUuids(UUID deadDungeonPlayerUuids) {
        this.deadDungeonPlayerUuids.add(deadDungeonPlayerUuids);
    }

    public void setDeadDungeonPlayerUuids(List<UUID> deadDungeonPlayerUuids) {
        this.deadDungeonPlayerUuids = deadDungeonPlayerUuids;
    }

    public List<UUID> getDeadDungeonPlayerUUIDs() {
        return this.deadDungeonPlayerUuids;
    }

    // NEW SYSTEM: Use DungeonDataManager for runtime data

    /**
     * Updates all runtime data at once without triggering multiple saves.
     * Use this during dungeon generation to avoid save spam.
     */
    public void updateAllRuntimeData(HashMap<Integer, ArrayList<BlockPos>> blockMap,
                                      List<BlockPos> chestPosList,
                                      List<BlockPos> exitPosList,
                                      List<BlockPos> gatePosList,
                                      Map<BlockPos, Integer> movingBlockMap,
                                      Map<BlockPos, Powered> poweredBlockMap,
                                      HashMap<BlockPos, String> spawnerPosEntityIdMap) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setBlockBlockPosMap(blockMap);
            data.setChestPosList(chestPosList);
            data.setExitPosList(exitPosList);
            data.setGatePosList(gatePosList);
            data.setMovingBlockMap(movingBlockMap);
            data.setPoweredBlockMap(poweredBlockMap);
            data.setSpawnerPosEntityIdMap(spawnerPosEntityIdMap);
            // Single save instead of 7 separate saves
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public void setBlockMap(HashMap<Integer, ArrayList<BlockPos>> map) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setBlockBlockPosMap(map);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public HashMap<Integer, ArrayList<BlockPos>> getBlockMap() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getBlockBlockPosMap();
        }
        return new HashMap<>();
    }

    public void setCooldownTime(int cooldownTime) {
        this.cooldownTime = cooldownTime;
        this.markDirty();
        if (this.world != null && !this.world.isClient()) {
            this.world.updateListeners(this.pos, this.getCachedState(), this.getCachedState(), Block.NOTIFY_ALL);
        }
    }

    public int getCooldownTime() {
        return this.cooldownTime;
    }

    public boolean isOnCooldown(int currentTime) {
        if (this.cooldownTime <= currentTime) {
            return false;
        }
        return true;
    }

    public void setMaxGroupSize(int maxGroupSize) {
        this.maxGroupSize = maxGroupSize;
    }

    public void setMinGroupSize(int minGroupSize) {
        this.minGroupSize = minGroupSize;
    }

    public List<UUID> getWaitingUuids() {
        return this.waitingUuids;
    }

    public void addWaitingUuid(UUID uuid) {
        if (!this.waitingUuids.contains(uuid)) {
            this.waitingUuids.add(uuid);
        }
    }

    public int getMaxGroupSize() {
        return this.maxGroupSize;
    }

    public int getMinGroupSize() {
        return this.minGroupSize;
    }

    public void setPrivateGroup(boolean privateGroup) {
        this.privateGroup = privateGroup;
    }

    public boolean getPrivateGroup() {
        return this.privateGroup;
    }

    public void setBossBlockPos(BlockPos pos) {
        this.bossBlockPos = pos;
    }

    public BlockPos getBossBlockPos() {
        return this.bossBlockPos;
    }

    public void setBossLootBlockPos(BlockPos pos) {
        this.bossLootBlockPos = pos;
    }

    public BlockPos getBossLootBlockPos() {
        return this.bossLootBlockPos;
    }

    public void setChestPosList(List<BlockPos> chestPosList) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setChestPosList(chestPosList);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public List<BlockPos> getChestPosList() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getChestPosList();
        }
        return new ArrayList<>();
    }

    public void setGatePosList(List<BlockPos> gatePosList) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setGatePosList(gatePosList);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public List<BlockPos> getGatePosList() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getGatePosList();
        }
        return new ArrayList<>();
    }

    public void setMovingBlockMap(Map<BlockPos, Integer> movingBlockMap) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setMovingBlockMap(movingBlockMap);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public Map<BlockPos, Integer> getMovingBlockMap() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getMovingBlockMap();
        }
        return new HashMap<>();
    }

    public void setPoweredBlockMap(Map<BlockPos, Powered> poweredBlockMap) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setPoweredBlockMap(poweredBlockMap);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public Map<BlockPos, Powered> getPoweredBlockMap() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getPoweredBlockMap();
        }
        return new HashMap<>();
    }

    public void setExitPosList(List<BlockPos> exitPosList) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setExitPosList(exitPosList);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public List<BlockPos> getExitPosList() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getExitPosList();
        }
        return new ArrayList<>();
    }

    public void addDungeonEdge(int edgeX, int edgeY, int edgeZ) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.addDungeonEdge(edgeX, edgeY, edgeZ);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public List<Integer> getDungeonEdgeList() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getDungeonEdgeList();
        }
        return new ArrayList<>();
    }

    public void setSpawnerPosEntityIdMap(HashMap<BlockPos, String> spawnerPosEntityIdMap) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setSpawnerPosEntityIdMap(spawnerPosEntityIdMap);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public HashMap<BlockPos, String> getSpawnerPosEntityIdMap() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getSpawnerPosEntityIdMap();
        }
        return new HashMap<>();
    }

    public void setReplaceBlockIdMap(HashMap<BlockPos, Integer> replacePosBlockIdMap) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.setReplacePosBlockIdMap(replacePosBlockIdMap);
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public void addReplaceBlockId(BlockPos pos, Block block) {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            DungeonRuntimeData data = DungeonDataManager.getData(serverWorld, this.pos);
            data.addReplaceBlockId(pos, Registries.BLOCK.getRawId(block));
            DungeonDataManager.saveData(serverWorld, this.pos, data);
        }
    }

    public HashMap<BlockPos, Integer> getReplaceBlockIdMap() {
        if (this.world != null && !this.world.isClient && this.world instanceof ServerWorld serverWorld) {
            return DungeonDataManager.getData(serverWorld, this.pos).getReplacePosBlockIdMap();
        }
        return new HashMap<>();
    }

    public void startDungeonTeleportCountdown(ServerWorld dungeonWorld) {
        this.dungeonTeleportCountdown = ConfigInit.CONFIG.defaultDungeonTeleportCountdown;

        // Fire the MythicDungeons hook first — it creates the RunInstance and
        // sets instanceOrigin so resolveOrigin() returns the right slot position.
        if (onCountdownStart != null) {
            onCountdownStart.accept(this);
        }

        BlockPos origin = this.resolveOrigin();

        // When an instance origin is set (Mythic+ run), always regenerate fresh.
        // Reset flags and stale bounding-box data so we always hit the generation branch.
        if (this.instanceOrigin != null) {
            this.dungeonStructureGenerated = false;
            this.getDungeonEdgeList().clear();
        }

        boolean isDungeonStructureGenerated = this.isDungeonStructureGenerated();

        if (!isDungeonStructureGenerated) {
            this.setDungeonStructureGenerated();
            DungeonPlacementHandler.clearArea(dungeonWorld, origin);
            DungeonPlacementHandler.generateDungeonStructure(dungeonWorld, origin, this);

        } else {
            if (ConfigInit.CONFIG.forcedRegeneration) {
                for (int i = 0; i < this.getDungeonPlayerUuids().size(); i++) {
                    ServerPlayerEntity player = (ServerPlayerEntity) dungeonWorld.getPlayerByUuid(this.getDungeonPlayerUuids().get(i));
                    if (player != null && DungeonHelper.getCurrentDungeon(player) != null) {
                        DungeonHelper.teleportOutOfDungeon(player);
                        player.sendMessage(Text.translatable("text.dungeonz.dungeon_safekick"));
                    }
                }
                DungeonPlacementHandler.clearDungeonAreaWithEntities(dungeonWorld, this);
                DungeonPlacementHandler.generateDungeonStructure(dungeonWorld, origin, this);
            } else {
                DungeonPlacementHandler.prepareDungeon(dungeonWorld, this);
            }
        }
        this.markDirty();
    }

    public int getdungeonTeleportCountdown() {
        return this.dungeonTeleportCountdown;
    }

    public static class Powered {
        private final int blockId;
        private final boolean powered;
        private final int facing;
        private final int blockFacing;

        public Powered(int blockId, boolean powered, int facing, int blockFacing) {
            this.blockId = blockId;
            this.powered = powered;
            this.facing = facing;
            this.blockFacing = blockFacing;
        }

        public int getBlockId() {
            return blockId;
        }

        public boolean getPowered() {
            return powered;
        }

        // Horizontal facing
        public int getFacing() {
            return facing;
        }

        // Block facing for example: cealing
        // 0 = none, 1 = ("floor"), 2 = WALL("wall"), 3 = CEILING("ceiling");
        public int getBlockFacing() {
            return blockFacing;
        }
    }

}
