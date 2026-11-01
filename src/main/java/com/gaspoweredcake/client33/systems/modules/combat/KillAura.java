/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.gaspoweredcake.client33.systems.modules.combat;

import com.gaspoweredcake.client33.events.packets.PacketEvent;
import com.gaspoweredcake.client33.events.entity.player.SendMovementPacketsEvent;
import com.gaspoweredcake.client33.events.world.TickEvent;
import com.gaspoweredcake.client33.pathing.PathManagers;
import com.gaspoweredcake.client33.settings.*;
import com.gaspoweredcake.client33.systems.friends.Friends;
import com.gaspoweredcake.client33.systems.modules.Categories;
import com.gaspoweredcake.client33.systems.modules.Module;
import com.gaspoweredcake.client33.systems.modules.Modules;
import com.gaspoweredcake.client33.utils.entity.EntityAgeTest;
import com.gaspoweredcake.client33.utils.entity.EntityUtils;
import com.gaspoweredcake.client33.utils.entity.SortPriority;
import com.gaspoweredcake.client33.utils.entity.Target;
import com.gaspoweredcake.client33.utils.entity.fakeplayer.FakePlayerManager;
import com.gaspoweredcake.client33.utils.misc.TickTimer;
import com.gaspoweredcake.client33.utils.entity.fakeplayer.FakePlayerEntity;
import com.gaspoweredcake.client33.utils.player.FindItemResult;
import com.gaspoweredcake.client33.utils.player.InvUtils;
import com.gaspoweredcake.client33.utils.player.PlayerUtils;
import com.gaspoweredcake.client33.utils.player.Rotations;
import com.gaspoweredcake.client33.utils.world.TickRate;
import com.gaspoweredcake.client33.utils.combat.CombatTelemetry;
import com.gaspoweredcake.client33.utils.combat.AttackCycle;
import com.gaspoweredcake.client33.utils.combat.TargetSelection;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class KillAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Boolean> serverCooldown = sgTiming.add(new BoolSetting.Builder()
        .name("server-cooldown").description("Estimate cooldown from server time packets, sent attacks and weapon speed. Falls back to 20 TPS until sampled; pauses on stale timing.")
        .defaultValue(true).build());
    private final Setting<Integer> cooldownMargin = sgTiming.add(new IntSetting.Builder()
        .name("cooldown-margin-ms").description("Extra timing margin for network jitter. Custom Delay overrides cooldown timing.")
        .defaultValue(25).range(0, 250).sliderRange(0, 250).build());

    // General

    private final Setting<AttackItems> attackWhenHolding = sgGeneral.add(new EnumSetting.Builder<AttackItems>()
        .name("attack-when-holding")
        .description("Only attacks an entity when a specified item is in your hand.")
        .defaultValue(AttackItems.Weapons)
        .build()
    );

    private final Setting<List<Item>> weapons = sgGeneral.add(new ItemListSetting.Builder()
        .name("selected-weapon-types")
        .description("Which types of weapons to attack with (if you select the diamond sword, any type of sword may be used to attack).")
        .defaultValue(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.TRIDENT)
        .filter(FILTER::contains)
        .visible(() -> attackWhenHolding.get() == AttackItems.Weapons)
        .build()
    );

    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotate")
        .description("Determines when you should rotate towards the target.")
        .defaultValue(RotationMode.Always)
        .build()
    );

    private final Setting<Target> aimAt = sgGeneral.add(new EnumSetting.Builder<Target>()
        .name("aim-at")
        .description("Which part of the target to aim at.")
        .defaultValue(Target.Body)
        .visible(() -> rotation.get() != RotationMode.None)
        .build()
    );

    private final Setting<AimMethod> aimMethod = sgGeneral.add(new EnumSetting.Builder<AimMethod>()
        .name("aim-method")
        .description("Choose the configured part, nearest point, or the nearest visible point on the target hitbox.")
        .defaultValue(AimMethod.ConfiguredPart)
        .visible(() -> rotation.get() != RotationMode.None)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to an acceptable weapon when attacking the target.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches to your previous slot when done attacking the target.")
        .defaultValue(false)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<ShieldMode> shieldMode = sgGeneral.add(new EnumSetting.Builder<ShieldMode>()
        .name("shield-mode")
        .description("""
                What to do when your target is blocking with a shield:
                - Ignore:   Don't attack them if they are blocking
                - Break:    Swap to an axe to disable the shield (Only if Auto Switch is enabled)
                - None:     Attack them as normal
            """)
        .defaultValue(ShieldMode.None)
        .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click")
        .description("Only attacks when holding left click.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyOnLook = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-look")
        .description("Only attacks when looking at an entity.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCombat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Freezes Baritone temporarily until you are finished attacking the entity.")
        .defaultValue(true)
        .build()
    );

    // Targeting

    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(EntityTypes.PLAYER)
        .build()
    );

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How to filter targets within range.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
        .name("max-targets")
        .description("How many entities to target at once.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 5)
        .visible(() -> !onlyOnLook.get())
        .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("The maximum range the entity can be to attack it.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("The maximum range the entity can be attacked through walls.")
        .defaultValue(3.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<EntityAgeTest> passiveMobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAgeTest>()
        .name("passive-mob-age-filter")
        .description("Determines the age of passive mobs to target (animals, villagers).")
        .defaultValue(EntityAgeTest.Adult)
        .build()
    );

    private final Setting<EntityAgeTest> hostileMobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAgeTest>()
        .name("hostile-mob-age-filter")
        .description("Determines the age of hostile mobs to target (zombies, piglins, hoglins, zoglins).")
        .defaultValue(EntityAgeTest.Both)
        .build()
    );

    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-named")
        .description("Whether or not to attack mobs with a name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignorePassive = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-passive")
        .description("Will only attack sometimes passive mobs if they are targeting you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-tamed")
        .description("Will avoid attacking mobs you tamed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> keepTarget = sgTargeting.add(new BoolSetting.Builder()
        .name("keep-target")
        .description("Keeps the primary target until it leaves range or becomes invalid.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> fieldOfView = sgTargeting.add(new DoubleSetting.Builder()
        .name("field-of-view")
        .description("Horizontal targeting cone in degrees. 360 targets all directions.")
        .defaultValue(360)
        .range(1, 360)
        .sliderRange(30, 360)
        .build()
    );

    // Timing

    private final Setting<Boolean> pauseOnLag = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Pauses if the server is lagging.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Does not attack while using an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnCA = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-CA")
        .description("Does not attack while CA is placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tpsSync = sgTiming.add(new BoolSetting.Builder()
        .name("TPS-sync")
        .description("Scales custom attack delays to the server tick rate.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customDelay = sgTiming.add(new BoolSetting.Builder()
        .name("custom-delay")
        .description("Use a custom delay instead of the vanilla cooldown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> hitDelay = sgTiming.add(new IntSetting.Builder()
        .name("hit-delay")
        .description("How fast you hit the entity in ticks.")
        .defaultValue(11)
        .min(0)
        .sliderMax(60)
        .visible(customDelay::get)
        .build()
    );

    private final Setting<Integer> switchDelay = sgTiming.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("How many ticks to wait before hitting an entity after switching hotbar slots.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final static Set<Item> FILTER = Set.of(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE, Items.MACE, Items.DIAMOND_SPEAR, Items.TRIDENT);
    private final List<Entity> targets = new ArrayList<>();
    private final TickTimer switchTimer = new TickTimer();
    private final TickTimer attackTimer = new TickTimer();
    private final AttackCycle attackCycle = new AttackCycle();
    private long preparedGeneration;
    private boolean prepared;
    private float targetingYaw;
    private int swappedSlot = -1;
    private boolean wasPathing = false;
    public boolean attacking, swapped;
    public static int previousSlot;

    public KillAura() {
        super(Categories.Combat, "kill-aura", "Attacks specified entities around you.");
    }

    @Override
    public void onActivate() {
        targets.clear();
        attacking = false;
        wasPathing = false;
        previousSlot = -1;
        swapped = false;
        swappedSlot = -1;
        switchTimer.reset();
        attackTimer.reset();
        attackCycle.invalidate();
    }

    @Override
    public void onDeactivate() {
        stopAttacking();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        prepared = false;
        preparedGeneration = attackCycle.begin();
        switchTimer.advance();
        attackTimer.advance();
        if (!canOperate()) { stopAttacking(); return; }
        targetingYaw = mc.player.getYRot();
        if (swapped && mc.player.getInventory().getSelectedSlot() != swappedSlot) {
            // Manual selection takes ownership of the hotbar back from this module.
            swapped = false;
            previousSlot = -1;
            swappedSlot = -1;
        }
        if (onlyOnLook.get()) {
            Entity targeted = mc.crosshairPickEntity;

            if (targeted == null || !entityCheck(targeted)) {
                stopAttacking();
                return;
            }

            targets.clear();
            targets.add(mc.crosshairPickEntity);
        } else {
            collectTargets();
        }

        if (targets.isEmpty()) {
            stopAttacking();
            return;
        }

        if (autoSwitch.get()) {
            int selectedSlot = mc.player.getInventory().getSelectedSlot();
            ItemStack heldItem = mc.player.getMainHandItem();
            FindItemResult weaponResult = new FindItemResult(selectedSlot, heldItem.getCount());

            if (shouldShieldBreak()) {
                if (!(heldItem.is(net.minecraft.tags.ItemTags.AXES))) {
                    weaponResult = InvUtils.find(itemStack -> itemStack.is(net.minecraft.tags.ItemTags.AXES), 0, 8);
                }
            } else if (!acceptableWeapon(heldItem)) {
                weaponResult = InvUtils.find(this::acceptableWeapon, 0, 8);
            }

            if (!weaponResult.found()) {
                stopAttacking();
                return;
            }

            if (weaponResult.slot() != selectedSlot) {
                if (!swapped) {
                    previousSlot = selectedSlot;
                    swapped = true;
                }
                if (!InvUtils.swap(weaponResult.slot(), false)) { stopAttacking(); return; }
                swappedSlot = weaponResult.slot();
            }
        }

        if (!acceptableWeapon(mc.player.getMainHandItem())) {
            stopAttacking();
            return;
        }

        attacking = true;
        if (pauseOnCombat.get() && PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        }

        prepared = true;
    }

    // The player tick has updated cooldowns and positions before movement submission.
    // Run before Rotations so its movement packet carries the aim used by the callback.
    @EventHandler(priority = 100)
    private void onMovement(SendMovementPacketsEvent.Pre event) {
        if (!prepared) return;
        prepared = false;
        long generation = preparedGeneration;
        if (!attackCycle.valid(generation) || !canOperate()) { stopAttacking(); return; }
        targets.removeIf(target -> !entityCheck(target));
        if (targets.isEmpty()) { stopAttacking(); return; }
        Entity primary = targets.getFirst();
        RotationMode rotationMode = rotation.get();
        if (delayCheck()) {
            var owner = mc.player;
            var world = mc.level;
            int slot = owner.getInventory().getSelectedSlot();
            ItemStack weapon = owner.getMainHandItem().copy();
            for (Entity target : List.copyOf(targets)) {
                Runnable hit = () -> {
                    if (!attackCycle.valid(generation) || !isActive() || !attacking) return;
                    if (!canOperate()) { stopAttacking(); return; }
                    if (mc.player != owner || mc.level != world || owner.getInventory().getSelectedSlot() != slot
                        || !ItemStack.isSameItemSameComponents(weapon, owner.getMainHandItem())
                        || !delayCheck() || !acceptableWeapon(owner.getMainHandItem()) || !entityCheck(target)) return;
                    if (attackCycle.claim(generation, customDelay.get() && hitDelay.get() == 0)) attack(target);
                };
                if (rotationMode == RotationMode.None) hit.run();
                else rotateTo(target, hit);
            }
        } else if (rotationMode == RotationMode.Always) {
            rotateTo(primary, null);
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundSetCarriedItemPacket) {
            switchTimer.waitTicks(switchDelay.get());
        }
    }

    private void stopAttacking() {
        prepared = false;
        attackCycle.invalidate();
        targets.clear();
        attacking = false;
        if (wasPathing) {
            PathManagers.get().resume();
            wasPathing = false;
        }
        if (swapBack.get() && swapped && previousSlot >= 0 && mc.player != null
            && mc.player.getInventory().getSelectedSlot() == swappedSlot) {
            InvUtils.swap(previousSlot, false);
        }
        swapped = false;
        previousSlot = -1;
        swappedSlot = -1;
    }

    private boolean canOperate() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || !mc.player.isAlive()
            || PlayerUtils.getGameMode() == GameType.SPECTATOR) return false;
        if (pauseOnUse.get() && (mc.gameMode.isDestroying() || mc.player.isUsingItem())) return false;
        if (onlyOnClick.get() && !mc.options.keyAttack.isDown()) return false;
        if (pauseOnLag.get() && TickRate.INSTANCE.getTimeSinceLastTick() >= 1f) return false;
        CrystalAura crystals = Modules.get().get(CrystalAura.class);
        return !pauseOnCA.get() || !crystals.isActive() || crystals.kaTimer <= 0;
    }

    private boolean shouldShieldBreak() {
        if (!autoSwitch.get() || shieldMode.get() != ShieldMode.Break) return false;

        for (Entity target : targets) {
            if (target instanceof Player player) {
                if (Modules.get().get(ShieldPredict.class).blocks(player)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean entityCheck(Entity entity) {
        // Recheck the current crosshair at movement submission and in delayed rotation callbacks.
        if (onlyOnLook.get() && mc.crosshairPickEntity != entity) return false;
        if (entity == mc.player || entity == mc.getCameraEntity() || entity.isRemoved() || entity.level() != mc.level) return false;
        if ((entity instanceof LivingEntity livingEntity && livingEntity.isDeadOrDying()) || !entity.isAlive())
            return false;

        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (entity instanceof Player player && player.isSpectator()) return false;

        Vec3 eye = mc.player.getEyePosition();
        AABB hitbox = entity.getBoundingBox();
        double dx = eye.x - Mth.clamp(eye.x, hitbox.minX, hitbox.maxX);
        double dy = eye.y - Mth.clamp(eye.y, hitbox.minY, hitbox.maxY);
        double dz = eye.z - Mth.clamp(eye.z, hitbox.minZ, hitbox.maxZ);
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > range.get() * range.get()) return false;
        double wallReach = Math.min(range.get(), wallsRange.get());
        if (distanceSquared > wallReach * wallReach && (rotation.get() != RotationMode.None && aimMethod.get() == AimMethod.VisibleHitbox
            ? visiblePoint(entity).isEmpty() : !PlayerUtils.canSeeEntity(entity))) return false;
        if (Math.abs(Mth.wrapDegrees(Rotations.getYaw(entity) - targetingYaw)) > fieldOfView.get() / 2) return false;
        if (ignoreTamed.get()) {
            if (entity instanceof OwnableEntity tameable
                && tameable.getOwner() != null
                && tameable.getOwner().equals(mc.player)
            ) return false;
        }
        if (ignorePassive.get()) {
            if (entity instanceof Enderman enderman && !enderman.isCreepy()) return false;
            if ((entity instanceof Piglin || entity instanceof ZombifiedPiglin || entity instanceof Wolf) && !((Mob) entity).isAggressive())
                return false;
        }
        if (entity instanceof Player player) {
            if (player.isCreative()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
            if (shieldMode.get() == ShieldMode.Ignore && Modules.get().get(ShieldPredict.class).blocks(player)) return false;
            if (player instanceof FakePlayerEntity fakePlayer && fakePlayer.noHit) return false;
        }
        if (entity instanceof LivingEntity livingEntity) {
            // Hostile mobs with baby variants (zombies, piglins, hoglins, zoglins)
            if (entity instanceof Zombie || entity instanceof Piglin
                || entity instanceof Hoglin || entity instanceof Zoglin) {
                return hostileMobAgeFilter.get().test(livingEntity);
            }
            // Passive mobs with baby variants (animals, villagers)
            if (entity instanceof AgeableMob && (!(entity instanceof Frog || entity instanceof Parrot))) {
                return passiveMobAgeFilter.get().test(livingEntity);
            }
        }
        return true;
    }

    private void collectTargets() {
        Entity previous = keepTarget.get() ? getTarget() : null;
        Comparator<RankedTarget> order = Comparator.comparingDouble(RankedTarget::score)
            .thenComparingInt(candidate -> candidate.entity().getId());
        RankedTarget retained = previous == null ? null : rank(previous);
        TargetSelection<RankedTarget> selection = new TargetSelection<>(maxTargets.get(), retained, order);
        for (Entity entity : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(range.get()), this::entityCheck)) {
            selection.add(rank(entity));
        }
        FakePlayerManager.forEach(player -> {
            if (entityCheck(player)) selection.add(rank(player));
        });
        targets.clear();
        for (RankedTarget candidate : selection.result()) targets.add(candidate.entity());
    }

    private RankedTarget rank(Entity entity) {
        double score = switch (priority.get()) {
            case ClosestAngle -> angleScore(entity);
            case LowestDistance -> PlayerUtils.squaredDistanceTo(entity);
            case HighestDistance -> -PlayerUtils.squaredDistanceTo(entity);
            case LowestHealth -> entity instanceof LivingEntity living ? living.getHealth() : Double.NEGATIVE_INFINITY;
            case HighestHealth -> entity instanceof LivingEntity living ? -living.getHealth() : Double.POSITIVE_INFINITY;
        };
        return new RankedTarget(entity, score);
    }

    private record RankedTarget(Entity entity, double score) {}

    private double angleScore(Entity entity) {
        double yaw = Mth.wrapDegrees(Rotations.getYaw(entity) - mc.player.getYRot());
        double pitch = Rotations.getPitch(entity) - mc.player.getXRot();
        return yaw * yaw + pitch * pitch;
    }

    private boolean delayCheck() {
        if (!switchTimer.ready()) return false;
        if (customDelay.get()) return attackTimer.ready();
        if (mc.player.getAttackStrengthScale(0) < 1) return false;
        return !serverCooldown.get() || CombatTelemetry.CLOCK.ready(CombatTelemetry.now(),
            mc.player.getCurrentItemAttackStrengthDelay(), cooldownMargin.get());
    }

    private void attack(Entity target) {
        mc.gameMode.attack(mc.player, target);
        com.gaspoweredcake.client33.utils.player.SwingUtils.swing(InteractionHand.MAIN_HAND);

        attackTimer.waitTicks(TickTimer.serverDelay(hitDelay.get(), TickRate.INSTANCE.getTickRate(), tpsSync.get()));
    }

    private void rotateTo(Entity target, Runnable callback) {
        if (aimMethod.get() == AimMethod.VisibleHitbox) {
            var point = visiblePoint(target);
            if (point.isPresent()) {
                Rotations.rotate(Rotations.getYaw(point.get()), Rotations.getPitch(point.get()), callback);
                return;
            }
        }
        if (aimMethod.get() == AimMethod.ClosestHitbox || aimMethod.get() == AimMethod.VisibleHitbox) {
            Vec3 eye = mc.player.getEyePosition();
            AABB box = target.getBoundingBox();
            if (!box.contains(eye)) {
                double inset = Math.min(0.02, Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) / 4);
                Vec3 point = new Vec3(Mth.clamp(eye.x, box.minX + inset, box.maxX - inset),
                    Mth.clamp(eye.y, box.minY + inset, box.maxY - inset), Mth.clamp(eye.z, box.minZ + inset, box.maxZ - inset));
                Rotations.rotate(Rotations.getYaw(point), Rotations.getPitch(point), callback);
                return;
            }
        }
        Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, aimAt.get()), callback);
    }

    private java.util.Optional<Vec3> visiblePoint(Entity target) {
        Vec3 eye = mc.player.getEyePosition(); AABB box = target.getBoundingBox();
        var result = com.gaspoweredcake.client33.utils.combat.VisibleAim.find(
            new com.gaspoweredcake.client33.utils.combat.VisibleAim.Point(eye.x, eye.y, eye.z),
            new com.gaspoweredcake.client33.utils.combat.VisibleAim.Point(box.minX, box.minY, box.minZ),
            new com.gaspoweredcake.client33.utils.combat.VisibleAim.Point(box.maxX, box.maxY, box.maxZ), range.get(),
            p -> mc.level.clip(new net.minecraft.world.level.ClipContext(eye, new Vec3(p.x(), p.y(), p.z()),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player))
                .getType() == net.minecraft.world.phys.HitResult.Type.MISS);
        return result.map(p -> new Vec3(p.x(), p.y(), p.z()));
    }

    private boolean acceptableWeapon(ItemStack stack) {
        if (shouldShieldBreak()) return stack.is(net.minecraft.tags.ItemTags.AXES);
        if (attackWhenHolding.get() == AttackItems.All) return true;

        if (weapons.get().contains(Items.DIAMOND_SWORD) && stack.is(ItemTags.SWORDS)) return true;
        if (weapons.get().contains(Items.DIAMOND_AXE) && stack.is(ItemTags.AXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_PICKAXE) && stack.is(ItemTags.PICKAXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_SHOVEL) && stack.is(ItemTags.SHOVELS)) return true;
        if (weapons.get().contains(Items.DIAMOND_HOE) && stack.is(ItemTags.HOES)) return true;
        if (weapons.get().contains(Items.MACE) && stack.getItem() instanceof MaceItem) return true;
        if (weapons.get().contains(Items.DIAMOND_SPEAR) && stack.is(ItemTags.SPEARS)) return true;
        return weapons.get().contains(Items.TRIDENT) && stack.getItem() instanceof TridentItem;
    }

    public Entity getTarget() {
        if (!targets.isEmpty()) return targets.getFirst();
        return null;
    }

    @Override
    public String getInfoString() {
        if (!targets.isEmpty()) return EntityUtils.getName(getTarget());
        return null;
    }

    public enum AimMethod { ConfiguredPart, ClosestHitbox, VisibleHitbox }

    public enum AttackItems {
        Weapons,
        All
    }

    public enum RotationMode {
        Always,
        OnHit,
        None
    }

    public enum ShieldMode {
        Ignore,
        Break,
        None
    }

}
