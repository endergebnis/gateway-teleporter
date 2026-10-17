package gg.endergebnis.gatewaytp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.EndGateway;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gepaarte Teleporter auf Basis des End-Gateways.
 *
 * Ablauf: Rezept ergibt 2 Teleporter. Der erste platzierte wartet ("pending"),
 * der zweite wird mit ihm verbunden. Danach setzt das Plugin bei beiden Gateways
 * die Exit-Position (BlockEntity-NBT: exit_portal + exact_teleport) auf die Seite
 * des jeweils anderen. Das eigentliche Teleportieren (Spieler, Mobs, Items,
 * Projektile, Loren ...) macht dann Vanilla.
 */
public final class GatewayTeleporterPlugin extends JavaPlugin implements Listener {

    private NamespacedKey itemKey;
    private NamespacedKey recipeKey;
    private File dataFile;

    /** Alle platzierten Teleporter (Block-Keys). */
    private final Set<String> known = new HashSet<>();
    /** Verbindungen, immer in beide Richtungen eingetragen. */
    private final Map<String, String> links = new HashMap<>();
    /** Pro Spieler: zuletzt platzierter, noch unverbundener Teleporter. */
    private final Map<UUID, String> pending = new HashMap<>();

    private static final BlockFace[] SIDES = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "teleporter");
        recipeKey = new NamespacedKey(this, "teleporter_pair");
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        saveData();
        Bukkit.removeRecipe(recipeKey);
    }

    // ------------------------------------------------------------------ Item & Rezept

    private ItemStack createItem(int amount) {
        ItemStack item = new ItemStack(Material.END_GATEWAY, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Gateway-Teleporter", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Platziere zwei Teleporter, um sie zu verbinden.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Abbauen: Sneaken + Rechtsklick mit leerer Hand.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isTeleporterItem(ItemStack item) {
        if (item == null || item.getType() != Material.END_GATEWAY || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createItem(2));
        recipe.shape("#R#", "EeE", "#L#");
        recipe.setIngredient('#', Material.OBSIDIAN);
        recipe.setIngredient('R', Material.RECOVERY_COMPASS);
        recipe.setIngredient('E', Material.END_CRYSTAL);
        recipe.setIngredient('e', Material.ENDER_CHEST);
        recipe.setIngredient('L', Material.LODESTONE);
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(recipeKey);
    }

    // ------------------------------------------------------------------ Platzieren & Verbinden

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isTeleporterItem(e.getItemInHand())) return;

        Player p = e.getPlayer();
        Block placed = e.getBlockPlaced();
        String pendingKey = pending.get(p.getUniqueId());
        Block other = pendingKey == null ? null : resolve(pendingKey);

        boolean validPending = other != null
                && other.getType() == Material.END_GATEWAY
                && known.contains(pendingKey)
                && !links.containsKey(pendingKey);

        if (validPending && !other.getWorld().equals(placed.getWorld())) {
            p.sendMessage(Component.text("Beide Teleporter müssen in derselben Welt stehen.", NamedTextColor.RED));
            e.setCancelled(true);
            return;
        }

        BlockFace facing = p.getFacing();
        Bukkit.getScheduler().runTask(this, () -> {
            if (placed.getType() != Material.END_GATEWAY) return;
            String key = key(placed);
            known.add(key);

            if (validPending && other.getType() == Material.END_GATEWAY) {
                link(placed, other, facing);
                pending.remove(p.getUniqueId());
                p.sendActionBar(Component.text("Teleporter verbunden!", NamedTextColor.GREEN));
            } else {
                pending.put(p.getUniqueId(), key);
                p.sendActionBar(Component.text("Teleporter platziert - platziere jetzt den zweiten.", NamedTextColor.YELLOW));
            }
            saveData();
        });
    }

    private void link(Block a, Block b, BlockFace preferred) {
        setExit(a, exitFor(b, preferred));
        setExit(b, exitFor(a, preferred.getOppositeFace()));
        links.put(key(a), key(b));
        links.put(key(b), key(a));
    }

    /** Setzt die Exit-Position im Gateway (entspricht exit_portal + exact_teleport im NBT). */
    private void setExit(Block gateway, Location exit) {
        if (!(gateway.getState() instanceof EndGateway state)) return;
        state.setExitLocation(exit);
        state.setExactTeleport(true);
        state.update(true, false);
    }

    /**
     * Austrittspunkt neben dem Gateway. Direkt darüber geht nicht: das Gateway hat keine
     * Kollision, man würde sofort wieder hineinfallen und zurückspringen.
     */
    private Location exitFor(Block gateway, BlockFace preferred) {
        List<BlockFace> order = new ArrayList<>();
        if (preferred != BlockFace.UP && preferred != BlockFace.DOWN) order.add(preferred);
        for (BlockFace f : SIDES) if (!order.contains(f)) order.add(f);

        for (BlockFace f : order) {
            Block feet = gateway.getRelative(f);
            Block head = feet.getRelative(BlockFace.UP);
            Block ground = feet.getRelative(BlockFace.DOWN);
            if (isFree(feet) && isFree(head) && !ground.isPassable()) {
                return exitLocation(feet, f);
            }
        }
        // Fallback: bevorzugte Seite, auch wenn nicht ideal
        return exitLocation(gateway.getRelative(order.get(0)), order.get(0));
    }

    private boolean isFree(Block b) {
        return b.isPassable() && b.getType() != Material.END_GATEWAY && !b.isLiquid();
    }

    private Location exitLocation(Block feet, BlockFace facing) {
        Location loc = feet.getLocation().add(0.5, 0, 0.5);
        loc.setYaw(switch (facing) {
            case NORTH -> 180f;
            case EAST -> 270f;
            case WEST -> 90f;
            default -> 0f;
        });
        return loc;
    }

    // ------------------------------------------------------------------ Abbauen

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!known.contains(key(b))) return;
        e.setCancelled(true);
        removeTeleporter(b, e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (b == null || !p.isSneaking() || !known.contains(key(b))) return;
        if (!p.getInventory().getItemInMainHand().getType().isAir()) return;
        e.setCancelled(true);
        removeTeleporter(b, p);
    }

    private void removeTeleporter(Block b, Player p) {
        String key = key(b);
        known.remove(key);
        b.setType(Material.AIR, false);

        String partner = links.remove(key);
        if (partner != null) {
            links.remove(partner);
            // Wer abbaut, kann mit dem Item den Partner wieder verbinden.
            pending.put(p.getUniqueId(), partner);
        }
        pending.values().removeIf(v -> v.equals(key));
        if (partner != null) pending.put(p.getUniqueId(), partner);

        ItemStack drop = createItem(1);
        Map<Integer, ItemStack> left = p.getInventory().addItem(drop);
        left.values().forEach(i -> b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), i));
        saveData();
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> known.contains(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> known.contains(key(b)));
    }

    // ------------------------------------------------------------------ Unverbundene sperren

    /** Ein unverbundener Teleporter soll niemanden irgendwohin schicken. */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.END_GATEWAY) return;
        if (isUnlinkedAt(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent e) {
        if (e.getFrom().getBlock().getType() != Material.END_GATEWAY) return;
        if (isUnlinkedAt(e.getFrom())) e.setCancelled(true);
    }

    private boolean isUnlinkedAt(Location from) {
        String key = key(from.getBlock());
        return known.contains(key) && !links.containsKey(key);
    }

    // ------------------------------------------------------------------ Befehl

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof Player pl ? pl : null);
            if (target == null) {
                sender.sendMessage(Component.text("Spieler nicht gefunden.", NamedTextColor.RED));
                return true;
            }
            int amount = 2;
            if (args.length >= 3) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("Ungültige Anzahl.", NamedTextColor.RED));
                    return true;
                }
            }
            target.getInventory().addItem(createItem(amount))
                    .values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));
            sender.sendMessage(Component.text(amount + " Teleporter an " + target.getName() + " gegeben.", NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("Benutzung: /gatewaytp give [spieler] [anzahl]", NamedTextColor.GRAY));
        return true;
    }

    // ------------------------------------------------------------------ Persistenz

    private String key(Block b) {
        return b.getWorld().getUID() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private Block resolve(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) return null;
        World w = Bukkit.getWorld(UUID.fromString(parts[0]));
        if (w == null) return null;
        return w.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        known.addAll(cfg.getStringList("known"));
        ConfigurationSection l = cfg.getConfigurationSection("links");
        if (l != null) {
            for (String idx : l.getKeys(false)) {
                String a = l.getString(idx + ".a");
                String b = l.getString(idx + ".b");
                if (a != null && b != null) links.put(a, b);
            }
        }
        ConfigurationSection pe = cfg.getConfigurationSection("pending");
        if (pe != null) {
            for (String uuid : pe.getKeys(false)) {
                String v = pe.getString(uuid);
                if (v != null) pending.put(UUID.fromString(uuid), v);
            }
        }
    }

    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("known", new ArrayList<>(known));
        int i = 0;
        for (Map.Entry<String, String> en : links.entrySet()) {
            cfg.set("links." + i + ".a", en.getKey());
            cfg.set("links." + i + ".b", en.getValue());
            i++;
        }
        for (Map.Entry<UUID, String> en : pending.entrySet()) {
            cfg.set("pending." + en.getKey(), en.getValue());
        }
        try {
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("data.yml konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }
}
