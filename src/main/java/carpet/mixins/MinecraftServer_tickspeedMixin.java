package carpet.mixins;

import carpet.fakes.MinecraftServerInterface;
import carpet.helpers.ServerTickRateManager;
import carpet.patches.CopyProfilerResult;
import carpet.utils.CarpetProfiler;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.EmptyProfileResults;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(value = MinecraftServer.class, priority = Integer.MAX_VALUE - 10)
public abstract class MinecraftServer_tickspeedMixin extends ReentrantBlockableEventLoop<TickTask> implements MinecraftServerInterface
{
    @Shadow private volatile boolean running;

    @Shadow private long nextTickTime;

    @Shadow @Final private static Logger LOGGER;

    @Shadow private ProfilerFiller profiler;

    public MinecraftServer_tickspeedMixin(String name)
    {
        super(name);
    }

    @Shadow protected abstract void tickServer(BooleanSupplier booleanSupplier_1);

    @Shadow protected abstract boolean haveTime();

    @Shadow private long delayedTasksMaxNextTickTime;

    @Shadow private volatile boolean isReady;

    @Shadow private long lastOverloadWarning;

    @Shadow private boolean mayHaveDelayedTasks;

    @Shadow public abstract Iterable<ServerLevel> getAllLevels();

    @Shadow private int tickCount;

    @Shadow protected abstract void waitUntilNextTick();

    @Shadow protected abstract void startMetricsRecordingTick();

    @Shadow protected abstract void endMetricsRecordingTick();

    @Shadow private boolean debugCommandProfilerDelayStart;
    CarpetProfiler.ProfilerToken currentSection;

    private float carpetMsptAccum = 0.0f;

    private ServerTickRateManager serverTickRateManager;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci)
    {
        serverTickRateManager = new ServerTickRateManager((MinecraftServer)(Object)this);
    }

    @Override
    public ServerTickRateManager getTickRateManager()
    {
        return serverTickRateManager;
    }

    /**
     * To ensure compatibility with other mods we should allow milliseconds
     */

    private long millisBehind = 0;

    private void determineTickSpeed() {
        if (CarpetProfiler.tick_health_requested != 0L)
        {
            CarpetProfiler.start_tick_profiling();
        }
        long msThisTick = 0L;
        float mspt = serverTickRateManager.mspt();
        if (serverTickRateManager.isInWarpSpeed() && serverTickRateManager.continueWarp())
        {
            //making sure server won't flop after the warp or if the warp is interrupted
            this.nextTickTime = this.lastOverloadWarning = Util.getMillis();
            carpetMsptAccum = mspt;
            millisBehind = 0;
        }
        else
        {
            if (Math.abs(carpetMsptAccum - mspt) > 1.0f)
            {
                // Tickrate changed. Ensure that we use the correct value.
                carpetMsptAccum = mspt;
            }

            msThisTick = (long)carpetMsptAccum; // regular tick
            carpetMsptAccum += mspt - msThisTick;

            millisBehind = Util.getMillis() - this.nextTickTime;
        }
    }

    @ModifyConstant(method = "runServer", constant = @Constant(longValue = 2000))
    private long maxMillisBehind(long original)
    {
        return 1000L + (long)(20 * serverTickRateManager.mspt());
    }

    @ModifyConstant(method = "runServer", constant = @Constant(longValue = 15000))
    private long overloadWarningCooldown(long original)
    {
        return 10000L + (long)(100 * serverTickRateManager.mspt());
    }

    @ModifyConstant(method = "runServer", constant = @Constant(longValue = 50))
    private long millisPerTick(long original)
    {
        return (long)serverTickRateManager.mspt();
    }

    @ModifyVariable(method = "runServer", at = @At("STORE"), ordinal = 0)
    private long millisBehind(long original)
    {
        determineTickSpeed();
        return millisBehind;
    }

    @Inject(method = "runServer", at = @At(value = "FIELD", target = "Lnet/minecraft/server/MinecraftServer;mayHaveDelayedTasks:Z", ordinal = 0))
    private void clearTaskQueue(CallbackInfo ci)
    {
        // clearing all hanging tasks no matter what when warping
        if (serverTickRateManager.isInWarpSpeed()) {
            while(this.runEveryTask()) {Thread.yield();}
        }
    }

    @Inject(method = "haveTime", at = @At(value = "HEAD"), cancellable = true)
    private void haveDowntime(CallbackInfoReturnable<Boolean> cir)
    {
        // do not handle tasks in the 'downtime' between ticks when warping
        // they are run/cleared in the main loop when warping, see above
        if (serverTickRateManager.isInWarpSpeed()) {
            cir.setReturnValue(false);
        }
    }

    // just because profilerTimings class is public
    Pair<Long,Integer> profilerTimings = null;
    /// overworld around profiler timings
    @Inject(method = "isTimeProfilerRunning", at = @At("HEAD"), cancellable = true)
    public void isCMDebugRunning(CallbackInfoReturnable<Boolean> cir)
    {
        cir.setReturnValue(debugCommandProfilerDelayStart || profilerTimings != null);
    }
    @Inject(method = "stopTimeProfiler", at = @At("HEAD"), cancellable = true)
    public void stopCMDebug(CallbackInfoReturnable<ProfileResults> cir)
    {
        if (this.profilerTimings == null) {
            cir.setReturnValue(EmptyProfileResults.EMPTY);
        } else {
            ProfileResults profileResult = new CopyProfilerResult(
                    profilerTimings.getRight(), profilerTimings.getLeft(),
                    this.tickCount, Util.getNanos()
            );
            this.profilerTimings = null;
            cir.setReturnValue(profileResult);
        }
    }


    private boolean runEveryTask() {
        if (super.pollTask()) {
            return true;
        } else {
            if (true) { // unconditionally this time
                for(ServerLevel serverlevel : getAllLevels()) {
                    if (serverlevel.getChunkSource().pollTask()) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    @Inject(method = "tickServer", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;saveEverything(ZZZ)Z", // save
            shift = At.Shift.BEFORE
    ))
    private void startAutosave(BooleanSupplier booleanSupplier_1, CallbackInfo ci)
    {
        currentSection = CarpetProfiler.start_section(null, "Autosave", CarpetProfiler.TYPE.GENERAL);
    }

    @Inject(method = "tickServer", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;saveEverything(ZZZ)Z",
            shift = At.Shift.AFTER
    ))
    private void finishAutosave(BooleanSupplier booleanSupplier_1, CallbackInfo ci)
    {
        CarpetProfiler.end_current_section(currentSection);
    }

    @Inject(method = "tickChildren", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getConnection()Lnet/minecraft/server/network/ServerConnectionListener;",
            shift = At.Shift.BEFORE
    ))
    private void startNetwork(BooleanSupplier booleanSupplier_1, CallbackInfo ci)
    {
        currentSection = CarpetProfiler.start_section(null, "Network", CarpetProfiler.TYPE.GENERAL);
    }

    @Inject(method = "tickChildren", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;tick()V",
            shift = At.Shift.AFTER
    ))
    private void finishNetwork(BooleanSupplier booleanSupplier_1, CallbackInfo ci)
    {
        CarpetProfiler.end_current_section(currentSection);
    }

    @Inject(method = "waitUntilNextTick", at = @At("HEAD"))
    private void startAsync(CallbackInfo ci)
    {
        currentSection = CarpetProfiler.start_section(null, "Async Tasks", CarpetProfiler.TYPE.GENERAL);
    }
    @Inject(method = "waitUntilNextTick", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;managedBlock(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.BEFORE
    ))
    private void stopAsync(CallbackInfo ci)
    {
        if (CarpetProfiler.tick_health_requested != 0L)
        {
            CarpetProfiler.end_current_section(currentSection);
            CarpetProfiler.end_tick_profiling((MinecraftServer) (Object)this);
        }
    }


}
