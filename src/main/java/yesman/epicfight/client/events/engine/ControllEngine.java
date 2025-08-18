package yesman.epicfight.client.events.engine;

import java.util.Set;
import org.lwjgl.glfw.GLFW;
import com.google.common.collect.Sets;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.client.gui.screen.IngameConfigurationScreen;
import yesman.epicfight.client.gui.screen.SkillEditScreen;
import yesman.epicfight.client.input.EpicFightKeyMappings;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.main.EpicFightMod;
import yesman.epicfight.network.EpicFightNetworkManager;
import yesman.epicfight.skill.ChargeableSkill;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillSlot;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.entity.eventlistener.MovementInputEvent;
import yesman.epicfight.world.entity.eventlistener.PlayerEventListener.EventType;
import yesman.epicfight.world.entity.eventlistener.SkillExecuteEvent;
import yesman.epicfight.world.gamerule.EpicFightGamerules;

@OnlyIn(Dist.CLIENT)
public class ControllEngine {

    private final Set<Object> packets = Sets.newHashSet();
    private final Minecraft minecraft;
    private LocalPlayer player;
    private LocalPlayerPatch playerpatch;

    private int weaponInnatePressCounter = 0;
    private int sneakPressCounter = 0;
    private int moverPressCounter = 0;
    private int lastHotbarLockedTime;
    private boolean weaponInnatePressToggle = false;
    private boolean sneakPressToggle = false;
    private boolean moverPressToggle = false;
    private boolean attackLightPressToggle = false;
    private boolean hotbarLocked;
    private boolean chargeKeyUnpressed;
    private int reserveCounter;
    private KeyMapping reservedKey;
    private SkillSlot reservedOrChargingSkillSlot;
    private KeyMapping currentChargingKey;

    public Options options;

    public ControllEngine() {
        Events.controllEngine = this;
        this.minecraft = Minecraft.getInstance();
        this.options = this.minecraft.options;

        // Asignar funciones de teclas
        this.keyFunctions.put(EpicFightKeyMappings.ATTACK, this::attackKeyPressed);
        this.keyFunctions.put(this.options.keySwapOffhand, this::swapHandKeyPressed);
        this.keyFunctions.put(EpicFightKeyMappings.SWITCH_MODE, this::switchModeKeyPressed);
        this.keyFunctions.put(EpicFightKeyMappings.DODGE, this::dodgeKeyPressed);
        this.keyFunctions.put(EpicFightKeyMappings.GUARD, this::guardPressed);
        this.keyFunctions.put(EpicFightKeyMappings.WEAPON_INNATE_SKILL, this::weaponInnateSkillKeyPressed);
        this.keyFunctions.put(EpicFightKeyMappings.MOVER_SKILL, this::moverKeyPressed);
        this.keyFunctions.put(EpicFightKeyMappings.LOCK_ON, this::lockonPressed);
    }

    public void setPlayerPatch(LocalPlayerPatch playerpatch) {
        this.weaponInnatePressCounter = 0;
        this.weaponInnatePressToggle = false;
        this.sneakPressCounter = 0;
        this.sneakPressToggle = false;
        this.attackLightPressToggle = false;
        this.player = playerpatch.getOriginal();
        this.playerpatch = playerpatch;
    }

    public LocalPlayerPatch getPlayerPatch() {
        return this.playerpatch;
    }

    public boolean canPlayerMove(EntityState playerState) {
        return !playerState.movementLocked() || this.player.isRidingJumpable();
    }

    public boolean canPlayerRotate(EntityState playerState) {
        return !playerState.turningLocked() || this.player.isRidingJumpable();
    }

    public void handleEpicFightKeyMappings() {
        if (!player.isAlive()) {
            releaseAllServedKeys();
            return;
        }

        if (keyPressed(EpicFightKeyMappings.SKILL_EDIT, false)) {
            if (this.playerpatch.getSkillCapability() != null) {
                Minecraft.getInstance().setScreen(new SkillEditScreen(this.player, this.playerpatch.getSkillCapability()));
            }
        }

        if (keyPressed(EpicFightKeyMappings.CONFIG, false)) {
            Minecraft.getInstance().setScreen(new IngameConfigurationScreen(this.minecraft, null));
        }

        // Desactivar teclas hotbar si el jugador está inactivo
        if (this.playerpatch.getEntityState().inaction() || this.hotbarLocked) {
            for (int i = 0; i < 9; i++) while (this.options.keyHotbarSlots[i].consumeClick());
            while (this.options.keyDrop.consumeClick());
        }

        tick();
    }

    private void tick() {
        if (this.playerpatch == null || !this.playerpatch.isBattleMode() || Minecraft.getInstance().isPaused()) return;
        if (!player.isAlive()) { releaseAllServedKeys(); return; }

        // Desbloqueo automático hotbar
        if (this.player.tickCount - this.lastHotbarLockedTime > 20 && this.hotbarLocked) unlockHotkeys();

        // Weapon Innate
        if (this.weaponInnatePressToggle) {
            if (!isKeyDown(EpicFightKeyMappings.WEAPON_INNATE_SKILL)) {
                this.attackLightPressToggle = true;
                this.weaponInnatePressToggle = false;
                this.weaponInnatePressCounter = 0;
            } else {
                SkillContainer skill = this.playerpatch.getSkill(SkillSlots.WEAPON_INNATE);
                if (weaponInnatePressCounter > EpicFightMod.CLIENT_INGAME_CONFIG.longPressCount.getValue()) {
                    if (skill.sendExecuteRequest(this.playerpatch, this).shouldReserverKey()) reserveKey(SkillSlots.WEAPON_INNATE, EpicFightKeyMappings.WEAPON_INNATE_SKILL);
                    else lockHotkeys();
                    weaponInnatePressToggle = false;
                    weaponInnatePressCounter = 0;
                } else weaponInnatePressCounter++;
            }
        }

        // Basic & Air Attack
        if (this.attackLightPressToggle) {
            SkillSlot slot = (!player.isOnGround() && !player.isInWater() && player.getDeltaMovement().y > 0.05D) ? SkillSlots.AIR_ATTACK : SkillSlots.BASIC_ATTACK;
            SkillContainer skill = playerpatch.getSkill(slot);
            if (skill.sendExecuteRequest(playerpatch, this).isExecutable()) releaseAllServedKeys();
            else if (!player.isSpectator() && slot == SkillSlots.BASIC_ATTACK) reserveKey(slot, EpicFightKeyMappings.ATTACK);
            lockHotkeys();
            attackLightPressToggle = false;
            weaponInnatePressToggle = false;
            weaponInnatePressCounter = 0;
        }

        // Sneak / Dodge
        if (this.sneakPressToggle) {
            if (!isKeyDown(this.options.keyShift)) {
                SkillSlot skillSlot = (playerpatch.getEntityState().knockDown()) ? SkillSlots.KNOCKDOWN_WAKEUP : SkillSlots.DODGE;
                SkillContainer skill = playerpatch.getSkill(skillSlot);
                if (skill.sendExecuteRequest(playerpatch, this).shouldReserverKey()) reserveKey(skillSlot, this.options.keyShift);
                sneakPressToggle = false;
                sneakPressCounter = 0;
            } else {
                if (sneakPressCounter > EpicFightMod.CLIENT_INGAME_CONFIG.longPressCount.getValue()) {
                    sneakPressToggle = false;
                    sneakPressCounter = 0;
                } else sneakPressCounter++;
            }
        }

        // Chargeable Skill
        if (currentChargingKey != null) {
            SkillContainer skill = playerpatch.getSkill(reservedOrChargingSkillSlot);
            if (!(skill.getSkill() instanceof ChargeableSkill chargingSkill)) { releaseAllServedKeys(); return; }
            if (!isKeyDown(currentChargingKey)) chargeKeyUnpressed = true;
            if (chargeKeyUnpressed && playerpatch.getSkillChargingTicks() > chargingSkill.getMinChargingTicks()) skill.sendExecuteRequest(playerpatch, this);
            if (playerpatch.getSkillChargingTicks() >= chargingSkill.getAllowedMaxChargingTicks()) releaseAllServedKeys();
        }

        // Reserved Skill
        if (reservedKey != null) {
            if (reserveCounter > 0) {
                SkillContainer skill = playerpatch.getSkill(reservedOrChargingSkillSlot);
                reserveCounter--;
                if (skill.getSkill() != null && skill.sendExecuteRequest(playerpatch, this).isExecutable()) {
                    releaseAllServedKeys();
                    lockHotkeys();
                }
            } else releaseAllServedKeys();
        }
    }

    private void inputTick(Input input) {
        if (!player.isAlive()) return;
        if (!canPlayerMove(playerpatch.getEntityState())) {
            input.forwardImpulse = 0F;
            input.leftImpulse = 0F;
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
            player.sprintTriggerTime = -1;
            player.setSprinting(false);
        }

        if (moverPressToggle) {
            if (!isKeyDown(options.keyJump)) {
                moverPressToggle = false;
                moverPressCounter = 0;
                if (player.isOnGround()) input.jumping = true;
            } else {
                if (moverPressCounter > EpicFightMod.CLIENT_INGAME_CONFIG.longPressCount.getValue()) {
                    playerpatch.getSkill(SkillSlots.MOVER).sendExecuteRequest(playerpatch, this);
                    moverPressToggle = false;
                    moverPressCounter = 0;
                } else {
                    input.jumping = false;
                    moverPressCounter++;
                }
            }
        }

        playerpatch.getEventListener().triggerEvents(EventType.MOVEMENT_INPUT_EVENT, new MovementInputEvent(playerpatch, input));
    }

    private void reserveKey(SkillSlot slot, KeyMapping keyMapping) {
        reservedKey = keyMapping;
        reservedOrChargingSkillSlot = slot;
        reserveCounter = 8;
    }

    private void releaseAllServedKeys() {
        chargeKeyUnpressed = true;
        currentChargingKey = null;
        reservedOrChargingSkillSlot = null;
        reserveCounter = -1;
        reservedKey = null;
    }

    public void setChargingKey(SkillSlot chargingSkillSlot, KeyMapping keyMapping) {
        chargeKeyUnpressed = false;
        currentChargingKey = keyMapping;
        reservedOrChargingSkillSlot = chargingSkillSlot;
        reserveCounter = -1;
        reservedKey = null;
    }

    public static boolean isKeyDown(KeyMapping key) {
        if (key.getKey().getType() == net.minecraft.client.util.InputConstants.Type.KEYSYM) {
            return key.isDown() || GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), key.getKey().getValue()) > 0;
        } else if(key.getKey().getType() == net.minecraft.client.util.InputConstants.Type.MOUSE) {
            return key.isDown() || GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), key.getKey().getValue()) > 0;
        } else return false;
    }

    public static void setKeyBind(KeyMapping key, boolean setter) {
        KeyMapping.set(key.getKey(), setter);
    }

    public void lockHotkeys() {
        hotbarLocked = true;
        lastHotbarLockedTime = player.tickCount;
        for (int i = 0; i < 9; i++) while (options.keyHotbarSlots[i].consumeClick());
    }

    public void unlockHotkeys() { hotbarLocked = false; }

    public void addPacketToSend(Object packet) { packets.add(packet); }

    private static boolean keyPressed(KeyMapping key, boolean eventCheck) {
        if (eventCheck) {
            int mouseButton = key.getKey().getType() == net.minecraft.client.util.InputConstants.Type.MOUSE ? key.getKey().getValue() : -1;
            InputEvent.InteractionKeyMappingTriggered inputEvent = net.minecraftforge.client.ForgeHooksClient.onClickInput(mouseButton, key, InteractionHand.MAIN_HAND);
            if (inputEvent.isCanceled()) return false;
        }
        return key.consumeClick();
    }

    public static void disableKey(KeyMapping keyMapping) { while(keyMapping.consumeClick()); setKeyBind(keyMapping, false); }

    @OnlyIn(Dist.CLIENT)
    @Mod.EventBusSubscriber(modid = EpicFightMod.MODID, value = Dist.CLIENT)
    public static class Events {
        static ControllEngine controllEngine;

        @SubscribeEvent
        public static void mouseScrollEvent(InputEvent.MouseScrollingEvent event) {
            if (controllEngine.minecraft.player != null && controllEngine.playerpatch != null && controllEngine.playerpatch.getEntityState().inaction()) {
                if (controllEngine.minecraft.screen == null) event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void moveInputEvent(MovementInputUpdateEvent event) {
            if (controllEngine.playerpatch != null) controllEngine.inputTick(event.getInput());
        }

        @SubscribeEvent
        public static void clientTickEndEvent(TickEvent.ClientTickEvent event) {
            if (controllEngine.minecraft.player == null) return;
            if (event.phase == TickEvent.Phase.END) {
                for (Object packet : controllEngine.packets) EpicFightNetworkManager.sendToServer(packet);
                controllEngine.packets.clear();
            }
        }
    }
}
