package yesman.epicfight.server;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.CapabilityManager;
import yesman.epicfight.compat.IBleeding;
import yesman.epicfight.main.EpicFightMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod.EventBusSubscriber(modid = EpicFightMod.MODID)
public class EpicFightServerEvents {

	private static final Logger LOGGER = LogManager.getLogger("EpicFight");

	// Block vanilla and modded attacks for downed players
	@SubscribeEvent
	public static void onLivingAttack(LivingAttackEvent event) {
		if (!(event.getEntity() instanceof Player player)) return;
		if (!player.level.isClientSide && isPlayerDowned(player)) {
			LOGGER.info("[EpicFight] Bloqueando ataque: jugador downed=" + player.getName().getString());
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public static void onLivingHurt(LivingHurtEvent event) {
		if (!(event.getEntity() instanceof Player player)) return;
		if (!player.level.isClientSide && isPlayerDowned(player)) {
			LOGGER.info("[EpicFight] Bloqueando daño: jugador downed=" + player.getName().getString());
			event.setCanceled(true);
		}
	}

	// Block right-click interactions (optional, for modded abilities)
	@SubscribeEvent
	public static void onPlayerInteract(PlayerInteractEvent event) {
		Player player = event.getEntity();
		if (!player.level.isClientSide && isPlayerDowned(player)) {
			LOGGER.info("[EpicFight] Bloqueando interacción: jugador downed=" + player.getName().getString());
			event.setCanceled(true);
		}
	}

	// Helper: check PlayerRevive capability
	private static boolean isPlayerDowned(Player player) {
		return player.getCapability(
			CapabilityManager.get(new CapabilityToken<IBleeding>() {})
		).resolve().map(IBleeding::isBleeding).orElse(false);
	}
}
