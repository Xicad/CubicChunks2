package io.github.opencubicchunks.cubicchunks.mixin.core.common.ticket;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Either;
import io.github.opencubicchunks.cubicchunks.chunk.VerticalViewDistanceListener;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.ChunkHolderAccess;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.TicketAccess;
import io.github.opencubicchunks.cubicchunks.server.level.CubeHolder;
import io.github.opencubicchunks.cubicchunks.server.level.CubeMap;
import io.github.opencubicchunks.cubicchunks.server.level.CubeTaskPriorityQueueSorter;
import io.github.opencubicchunks.cubicchunks.server.level.CubeTicketTracker;
import io.github.opencubicchunks.cubicchunks.server.level.CubicDistanceManager;
import io.github.opencubicchunks.cubicchunks.server.level.CubicPlayerTicketTracker;
import io.github.opencubicchunks.cubicchunks.server.level.CubicTicketType;
import io.github.opencubicchunks.cubicchunks.server.level.FixedPlayerDistanceCubeTracker;
import io.github.opencubicchunks.cubicchunks.utils.Coords;
import io.github.opencubicchunks.cubicchunks.utils.MathUtil;
import io.github.opencubicchunks.cubicchunks.world.level.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.chunk.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.chunk.LevelCube;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DistanceManager.class)
public abstract class MixinDistanceManager implements CubicDistanceManager, VerticalViewDistanceListener {

    @Final @Shadow Executor mainThreadExecutor;
    @Shadow private long ticketTickCounter;

    private final Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> cubeTickets = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerCube = new Long2ObjectOpenHashMap<>();

    private final Set<ChunkHolder> cubesToUpdateFutures = new HashSet<>();
    private final LongSet cubeTicketsToRelease = new LongOpenHashSet();

    private final CubeTicketTracker cubeTicketTracker = new CubeTicketTracker(this);
    private final FixedPlayerDistanceCubeTracker naturalSpawnCubeCounter = new FixedPlayerDistanceCubeTracker(this, 8 / CubeAccess.DIAMETER_IN_SECTIONS);
    private final CubicPlayerTicketTracker cubicPlayerTicketManager = new CubicPlayerTicketTracker(this, MathUtil.ceilDiv(33, CubeAccess.DIAMETER_IN_SECTIONS));
    private CubeTaskPriorityQueueSorter cubeTicketThrottler;
    private ProcessorHandle<CubeTaskPriorityQueueSorter.FunctionEntry<Runnable>> cubeTicketThrottlerInput;
    private ProcessorHandle<CubeTaskPriorityQueueSorter.RunnableEntry> cubeTicketThrottlerReleaser;

    private boolean isCubic;

    @Shadow private static int getTicketLevelAt(SortedArraySet<Ticket<?>> tickets) {
        throw new Error("Mixin did not apply correctly");
    }

    @Shadow abstract void addTicket(long position, Ticket<?> ticket);

    @Inject(method = "<init>", at = @At("RETURN"))
    public void init(Executor backgroundExecutor, Executor mainThreadExecutor_, CallbackInfo ci) {
        ProcessorHandle<Runnable> mainThreadHandle = ProcessorHandle.of("player ticket throttler", mainThreadExecutor_::execute);
        CubeTaskPriorityQueueSorter throttler = new CubeTaskPriorityQueueSorter(ImmutableList.of(mainThreadHandle), backgroundExecutor, 4);
        this.cubeTicketThrottler = throttler;
        this.cubeTicketThrottlerInput = throttler.createExecutor(mainThreadHandle, true);
        this.cubeTicketThrottlerReleaser = throttler.createSorterExecutor(mainThreadHandle);
    }

    @Override
    public void addCubeTicket(long cubePosIn, Ticket<?> ticketIn) {
        SortedArraySet<Ticket<?>> ticketsForCube = this.getCubeTickets(cubePosIn);
        int existingTicketLevel = getTicketLevelAt(ticketsForCube);

        // force a ticket on the cube's columns
        CubePos cubePos = CubePos.from(cubePosIn);
        for (int localX = 0; localX < CubeAccess.DIAMETER_IN_SECTIONS; localX++) {
            for (int localZ = 0; localZ < CubeAccess.DIAMETER_IN_SECTIONS; localZ++) {
                //do not need to handle region tickets due to the additional CCColumn tickets added in cube generation stages
                addTicket(CubePos.asChunkPosLong(cubePosIn, localX, localZ), TicketAccess.createNew(CubicTicketType.COLUMN, ticketIn.getTicketLevel(), cubePos));
            }
        }

        Ticket<?> cubeTicket = ticketsForCube.addOrGet(ticketIn);
        ((TicketAccess) cubeTicket).invokeSetCreatedTick(this.ticketTickCounter);
        if (ticketIn.getTicketLevel() < existingTicketLevel) {
            this.cubeTicketTracker.updateSourceLevel(cubePosIn, ticketIn.getTicketLevel(), true);
        }
    }

    @Override
    public <T> void addCubeTicket(TicketType<T> type, CubePos pos, int level, T value) {
        this.addCubeTicket(pos.asLong(), TicketAccess.createNew(type, level, value));
    }

    @Override
    public <T> void addCubeRegionTicket(TicketType<T> type, CubePos pos, int distance, T value) {
        this.addCubeTicket(pos.asLong(), TicketAccess.createNew(type, 33 - distance, value));
    }

    @Override
    public void removeCubeTicket(long cubePosLong, Ticket<?> ticketIn) {
        SortedArraySet<Ticket<?>> ticketsForCube = this.getCubeTickets(cubePosLong);
        ticketsForCube.remove(ticketIn);

        if (ticketsForCube.isEmpty()) {
            this.cubeTickets.remove(cubePosLong);
        }

        this.cubeTicketTracker.updateSourceLevel(cubePosLong, getTicketLevelAt(ticketsForCube), false);
        //We don't remove CCColumn tickets here, as chunks must always be loaded if a cube is loaded.
        //Chunk tickets are instead removed once a cube has been unloaded
    }

    @Override
    public <T> void removeCubeTicket(TicketType<T> type, CubePos pos, int level, T value) {
        Ticket<T> ticket = TicketAccess.createNew(type, level, value);
        this.removeCubeTicket(pos.asLong(), ticket);
    }

    @Override
    public <T> void removeCubeRegionTicket(TicketType<T> type, CubePos pos, int distance, T value) {
        Ticket<T> ticket = TicketAccess.createNew(type, 33 - distance, value);
        this.removeCubeTicket(pos.asLong(), ticket);
    }

    private SortedArraySet<Ticket<?>> getCubeTickets(long cubePosLong) {
        return this.cubeTickets.computeIfAbsent(cubePosLong, (pos) -> SortedArraySet.create(4));
    }

    @Inject(method = "runAllUpdates", at = @At("RETURN"), cancellable = true)
    public void runAllUpdatesCubicChunks(ChunkMap chunkManager, CallbackInfoReturnable<Boolean> cir) {
        if (!isCubic) {
            return;
        }
        this.naturalSpawnCubeCounter.processAllUpdates();
        this.cubicPlayerTicketManager.processAllUpdates();
        int updatesCompleted = Integer.MAX_VALUE - this.cubeTicketTracker.update(Integer.MAX_VALUE);
        if (!this.cubesToUpdateFutures.isEmpty()) {
            this.cubesToUpdateFutures.forEach((cubeHolder) -> ((ChunkHolderAccess) cubeHolder).invokeUpdateFutures(chunkManager, this.mainThreadExecutor));
            this.cubesToUpdateFutures.clear();
            cir.setReturnValue(true);
        } else {
            if (!this.cubeTicketsToRelease.isEmpty()) {
                LongIterator ticketsToRelease = this.cubeTicketsToRelease.iterator();

                while (ticketsToRelease.hasNext()) {
                    long cubePosToRelease = ticketsToRelease.nextLong();
                    if (this.getCubeTickets(cubePosToRelease).stream().anyMatch((cubeTicket) -> cubeTicket.getType() == CubicTicketType.PLAYER)) {
                        ChunkHolder cubeHolder = ((CubeMap) chunkManager).getUpdatingCubeIfPresent(cubePosToRelease);
                        if (cubeHolder == null) {
                            throw new IllegalStateException();
                        }

                        CompletableFuture<Either<LevelCube, ChunkHolder.ChunkLoadingFailure>> cubeEntityTickingFuture =
                            ((CubeHolder) cubeHolder).getCubeEntityTickingFuture();
                        cubeEntityTickingFuture.thenAccept((cubeEither) -> this.mainThreadExecutor.execute(() ->
                            this.cubeTicketThrottlerReleaser.tell(CubeTaskPriorityQueueSorter.createSorterMsg(() -> {
                            }, cubePosToRelease, false))));
                    }
                }
                this.cubeTicketsToRelease.clear();
            }
            boolean anyUpdatesCompleted = updatesCompleted != 0;
            cir.setReturnValue(anyUpdatesCompleted | cir.getReturnValueZ());
        }
    }

    /**
     * @author NotStirred
     * @reason CC must also update it's tracker&tickets
     */
    @Inject(method = "purgeStaleTickets", at = @At("RETURN"))
    protected void purgeStaleCubeTickets(CallbackInfo ci) {
        if (!isCubic) {
            return;
        }
        ObjectIterator<Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>>> cubeTicketsToPurge = this.cubeTickets.long2ObjectEntrySet().fastIterator();
        while (cubeTicketsToPurge.hasNext()) {
            Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> cubeTicketEntry = cubeTicketsToPurge.next();
            if (cubeTicketEntry.getValue().removeIf((ticket) -> ((TicketAccess) ticket).invokeTimedOut(this.ticketTickCounter))) {
                this.cubeTicketTracker.updateSourceLevel(cubeTicketEntry.getLongKey(), getTicketLevelAt(cubeTicketEntry.getValue()), false);
            }
            if (cubeTicketEntry.getValue().isEmpty()) {
                cubeTicketsToPurge.remove();
            }
        }
    }

    @Override
    public void updateCubeForced(CubePos pos, boolean add) {
        Ticket<CubePos> ticket = TicketAccess.createNew(CubicTicketType.FORCED, 31, pos);
        if (add) {
            this.addCubeTicket(pos.asLong(), ticket);
        } else {
            this.removeCubeTicket(pos.asLong(), ticket);
        }
    }

    // TODO: vanilla versions of these use section pos, can we just mix into them?
    @Override
    public void addCubePlayer(CubePos cubePos, ServerPlayer player) {
        long cubePosLong = cubePos.asLong();
        this.playersPerCube.computeIfAbsent(cubePosLong, (pos) -> new ObjectOpenHashSet<>()).add(player);
        this.naturalSpawnCubeCounter.updateSourceLevel(cubePosLong, 0, true);
        this.cubicPlayerTicketManager.updateSourceLevel(cubePosLong, 0, true);
    }

    @Override
    public void removeCubePlayer(CubePos cubePos, ServerPlayer player) {
        long cubePosLong = cubePos.asLong();
        ObjectSet<ServerPlayer> playersInCube = this.playersPerCube.get(cubePosLong);
        playersInCube.remove(player);
        if (playersInCube.isEmpty()) {
            this.playersPerCube.remove(cubePosLong);
            this.naturalSpawnCubeCounter.updateSourceLevel(cubePosLong, Integer.MAX_VALUE, false);
            this.cubicPlayerTicketManager.updateSourceLevel(cubePosLong, Integer.MAX_VALUE, false);
        }
    }

    /**
     * Returns the number of chunks taken into account when calculating the mob cap
     */
    @Override
    public int getNaturalSpawnCubeCount() {
        this.naturalSpawnCubeCounter.processAllUpdates();
        return this.naturalSpawnCubeCounter.cubesInRange.size();
    }

    @Override
    public boolean hasPlayersNearbyCube(long cubePosIn) {
        this.naturalSpawnCubeCounter.processAllUpdates();
        return this.naturalSpawnCubeCounter.cubesInRange.containsKey(cubePosIn);
    }

    @Override
    public Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> getCubeTickets() {
        return cubeTickets;
    }

    @Override
    public ProcessorHandle<CubeTaskPriorityQueueSorter.FunctionEntry<Runnable>> getCubeTicketThrottlerInput() {
        return cubeTicketThrottlerInput;
    }

    @Override
    public ProcessorHandle<CubeTaskPriorityQueueSorter.RunnableEntry> getCubeTicketThrottlerReleaser() {
        return cubeTicketThrottlerReleaser;
    }

    @Override
    public LongSet getCubeTicketsToRelease() {
        return cubeTicketsToRelease;
    }

    @Override
    public Set<ChunkHolder> getCubesToUpdateFutures() {
        return this.cubesToUpdateFutures;
    }

    @Override
    public Executor getMainThreadExecutor() {
        return mainThreadExecutor;
    }

    @Override
    public CubeTaskPriorityQueueSorter getCubeTicketThrottler() {
        return cubeTicketThrottler;
    }

    @Override
    public Long2ObjectMap<ObjectSet<ServerPlayer>> getPlayersPerCube() {
        return this.playersPerCube;
    }

    @Override
    public boolean hasCubePlayersNearby(long cubePos) {
        this.naturalSpawnCubeCounter.processAllUpdates();
        return this.naturalSpawnCubeCounter.cubesInRange.containsKey(cubePos);
    }

    @Override public void initCubic(boolean newIsCubic) {
        this.isCubic = newIsCubic;
    }

    @Override public void updatePlayerCubeTickets(int horizontalDistance, int verticalDistance) {
        this.cubicPlayerTicketManager.updateCubeViewDistance(Coords.sectionToCubeRenderDistance(horizontalDistance), Coords.sectionToCubeRenderDistance(verticalDistance));
    }
}