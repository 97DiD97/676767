package dev.eyesky;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Форма "тень": игрок для остальных превращается в чёрную сущность из частиц ростом 3 блока с белыми глазами.
 * Настоящая модель игрока скрывается (hideEntity), вместо неё рисуются частицы. Сам игрок свою тень не видит.
 */
public final class ShadeManager {

    private final EyeSkyPlugin plugin;
    private final Set<UUID> shades = new HashSet<>();
    private final Map<UUID, Integer> dissolving = new HashMap<>();   // кого сейчас изгоняют (осталось тиков)
    private static final int DISSOLVE_TICKS = 40;
    private double cutY = 99;                                        // выше этой высоты фигура уже рассыпалась
    private final List<Dummy> dummies = new ArrayList<>();               // тени, стоящие отдельно от игроков
    private int nextDummyId = 1;
    private final Random rnd = new Random();

    /** Тень-кукла: просто стоит на месте, глаз может её заметить и изгнать. */
    public static final class Dummy {
        public final int id;
        public final UUID uid = UUID.randomUUID();
        public final Location loc;
        int dissolveLeft = -1;

        Dummy(int id, Location loc) {
            this.id = id;
            this.loc = loc;
        }

        public boolean dissolving() {
            return dissolveLeft >= 0;
        }
    }

    public int spawnDummy(Location loc, float yaw) {
        Location l = loc.clone();
        l.setYaw(yaw);
        Dummy d = new Dummy(nextDummyId++, l);
        dummies.add(d);
        l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0, 1, 0), 40, 0.5, 0.9, 0.5, 0.05);
        l.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 0.5f);
        return d.id;
    }

    public List<Dummy> dummies() {
        return new ArrayList<>(dummies);
    }

    public int dummyCount() {
        return dummies.size();
    }

    public Dummy nearestDummy(Location from) {
        Dummy best = null;
        double bd = Double.MAX_VALUE;
        for (Dummy d : dummies) {
            if (d.dissolving() || !d.loc.getWorld().equals(from.getWorld())) continue;
            double dist = d.loc.distanceSquared(from);
            if (dist < bd) { bd = dist; best = d; }
        }
        return best;
    }

    public int removeDummies() {
        int n = dummies.size();
        dummies.clear();
        return n;
    }

    /** Запускает растворение куклы (вызывается лазером изгнания). */
    public void dissolveDummy(int id) {
        for (Dummy d : dummies) {
            if (d.id == id && !d.dissolving()) {
                d.dissolveLeft = DISSOLVE_TICKS;
                Location l = d.loc.clone().add(0, 1.5, 0);
                d.loc.getWorld().playSound(l, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.AMBIENT, 2.0f, 1.0f);
                d.loc.getWorld().playSound(l, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.AMBIENT, 2.0f, 0.5f);
            }
        }
    }
    private BukkitTask task;
    private long tick;

    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(4, 4, 8), 1.7f);
    private static final Particle.DustOptions DARK = new Particle.DustOptions(Color.fromRGB(18, 14, 26), 1.3f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.fromRGB(255, 255, 255), 0.9f);

    public ShadeManager(EyeSkyPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (UUID id : new HashSet<>(shades)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) show(p);
        }
        shades.clear();
        for (UUID id : new HashSet<>(dissolving.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) show(p);
        }
        dissolving.clear();
        dummies.clear();
    }

    public boolean isShade(Player p) {
        return p != null && shades.contains(p.getUniqueId());
    }

    public int count() {
        return shades.size();
    }

    /** Включает/выключает форму. Возвращает true, если теперь включена. */
    public boolean toggle(Player p) {
        if (isShade(p)) {
            disable(p);
            return false;
        }
        enable(p);
        return true;
    }

    public void enable(Player p) {
        dissolving.remove(p.getUniqueId());
        shades.add(p.getUniqueId());
        hideFromOthers(p);
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 40, 0.5, 0.9, 0.5, 0.05);
        p.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 0.5f);
    }

    public void disable(Player p) {
        if (dissolving.remove(p.getUniqueId()) != null) {
            show(p);
            return;
        }
        if (!shades.remove(p.getUniqueId())) return;
        show(p);
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 40, 0.5, 0.9, 0.5, 0.05);
        p.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    /** Глаз изгоняет тень: фигура растворяется сверху вниз около 2 секунд, потом игрок возвращается. */
    public void dispel(Player p) {
        if (!shades.remove(p.getUniqueId())) return;
        dissolving.put(p.getUniqueId(), DISSOLVE_TICKS);
        Location l = p.getLocation().add(0, 1.5, 0);
        p.getWorld().playSound(l, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.AMBIENT, 2.0f, 1.0f);
        p.getWorld().playSound(l, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.AMBIENT, 2.0f, 0.5f);
        p.sendMessage(Component.text("Глаз изгоняет тебя из тени!", NamedTextColor.GOLD));
    }

    private void finishDissolve(Player p) {
        show(p);
        Location l = p.getLocation().add(0, 1.2, 0);
        p.getWorld().spawnParticle(Particle.END_ROD, l, 80, 0.6, 1.0, 0.6, 0.25);
        p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l, 50, 0.5, 0.9, 0.5, 0.1);
        p.getWorld().spawnParticle(Particle.EXPLOSION, l, 1, 0, 0, 0, 0);
        p.getWorld().playSound(l, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.AMBIENT, 2.0f, 0.8f);
        p.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.AMBIENT, 1.5f, 1.4f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0));
        p.setVelocity(p.getVelocity().add(new Vector(0, 0.9, 0)));
    }

    /** Искры и пепел на уровне линии распада. */
    private void dissolveFx(Location b, double cut) {
        for (int i = 0; i < 10; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = 0.2 + rnd.nextDouble() * 0.5;
            double x = b.getX() + Math.cos(a) * d, y = b.getY() + cut + (rnd.nextDouble() - 0.5) * 0.3, z = b.getZ() + Math.sin(a) * d;
            b.getWorld().spawnParticle(Particle.END_ROD, x, y, z, 0, Math.cos(a) * 0.05, 0.25, Math.sin(a) * 0.05, 1.0);
            if (i % 3 == 0) b.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, x, y, z, 0, 0, 0.2, 0, 1.0);
        }
        b.getWorld().spawnParticle(Particle.ASH, b.getX(), b.getY() + cut * 0.5, b.getZ(), 6, 0.4, Math.max(0.1, cut * 0.4), 0.4, 0);
    }

    /** Новому игроку на сервере нужно скрыть всех, кто сейчас в тени. */
    public void onJoin(Player joiner) {
        Set<UUID> ids = new HashSet<>(shades);
        ids.addAll(dissolving.keySet());
        for (UUID id : ids) {
            Player s = Bukkit.getPlayer(id);
            if (s != null && !s.equals(joiner)) joiner.hideEntity(plugin, s);
        }
    }

    // =====================================================================================

    private void hideFromOthers(Player s) {
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (!v.equals(s)) v.hideEntity(plugin, s);
        }
    }

    private void show(Player s) {
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (!v.equals(s)) v.showEntity(plugin, s);
        }
    }

    private void tick() {
        try {
            tick++;
            for (UUID id : new HashSet<>(shades)) {
                Player s = Bukkit.getPlayer(id);
                if (s == null || !s.isOnline()) { shades.remove(id); continue; }
                if (tick % 10 == 0) hideFromOthers(s);          // на случай смены мира/респавна
                if (s.isDead()) continue;
                render(s);
            }
            for (UUID id : new HashSet<>(dissolving.keySet())) {
                Player s = Bukkit.getPlayer(id);
                if (s == null || !s.isOnline()) { dissolving.remove(id); continue; }
                int left = dissolving.get(id) - 2;                  // таймер тикает раз в 2 игровых тика
                if (left <= 0) {
                    dissolving.remove(id);
                    finishDissolve(s);
                    continue;
                }
                dissolving.put(id, left);
                if (tick % 10 == 0) hideFromOthers(s);
                double cut = 3.2 * left / DISSOLVE_TICKS;
                cutY = cut;
                render(s);                                          // рисуется только то, что ещё не рассыпалось
                cutY = 99;
                dissolveFx(s.getLocation(), cut);
            }
            for (Dummy d : new ArrayList<>(dummies)) {
                if (d.dissolving()) {
                    d.dissolveLeft -= 2;
                    if (d.dissolveLeft < 0) {
                        dummies.remove(d);
                        Location l = d.loc.clone().add(0, 1.2, 0);
                        d.loc.getWorld().spawnParticle(Particle.END_ROD, l, 80, 0.6, 1.0, 0.6, 0.25);
                        d.loc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l, 50, 0.5, 0.9, 0.5, 0.1);
                        d.loc.getWorld().spawnParticle(Particle.EXPLOSION, l, 1, 0, 0, 0, 0);
                        d.loc.getWorld().playSound(l, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.AMBIENT, 2.0f, 0.8f);
                        continue;
                    }
                    double cut = 3.2 * d.dissolveLeft / DISSOLVE_TICKS;
                    cutY = cut;
                    renderAt(d.loc, null);
                    cutY = 99;
                    dissolveFx(d.loc, cut);
                } else {
                    renderAt(d.loc, null);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Ошибка в форме тени", t);
        }
    }

    /** Рисует фигуру 3 блока высотой для всех, кроме самого игрока. */
    private void render(Player s) {
        renderAt(s.getLocation(), s);
    }

    private void renderAt(Location base, Player exclude) {
        double yaw = Math.toRadians(base.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);       // куда смотрит
        double rx = Math.cos(yaw), rz = Math.sin(yaw);        // вправо
        double sway = Math.sin(tick * 0.15) * 0.06;

        Set<Player> viewers = new HashSet<>();
        for (Player v : base.getWorld().getPlayers()) {
            if (exclude != null && v.equals(exclude)) continue;
            Location vl = v.getLocation();
            double dx = vl.getX() - base.getX(), dz = vl.getZ() - base.getZ();
            if (dx * dx + dz * dz < 40 * 40) viewers.add(v);
        }
        if (viewers.isEmpty()) return;

        // голова
        for (int i = 0; i < 12; i++) {
            Vector u = unit();
            put(viewers, base, rx, rz, fx, fz, u.getX() * 0.36, 2.65 + u.getY() * 0.38, u.getZ() * 0.36, BLACK);
        }
        // туловище
        for (int i = 0; i < 16; i++) {
            Vector u = unit();
            put(viewers, base, rx, rz, fx, fz, u.getX() * 0.42, 1.75 + u.getY() * 0.55, u.getZ() * 0.24, BLACK);
        }
        // руки
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 6; i++) {
                double k = i / 5.0;
                put(viewers, base, rx, rz, fx, fz, side * (0.5 + 0.12 * k) + sway * side, 2.15 - 1.1 * k, 0.0, DARK);
            }
        }
        // ноги
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 6; i++) {
                double k = i / 5.0;
                put(viewers, base, rx, rz, fx, fz, side * 0.18, 1.2 * (1 - k), 0.0, DARK);
            }
        }
        // клочья дыма у земли и вокруг
        for (int i = 0; i < 4; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = 0.3 + rnd.nextDouble() * 0.4;
            double x = base.getX() + Math.cos(a) * d, y = base.getY() + rnd.nextDouble() * 2.8, z = base.getZ() + Math.sin(a) * d;
            for (Player v : viewers) v.spawnParticle(Particle.LARGE_SMOKE, x, y, z, 0, 0, 0.02, 0, 1.0);
        }
        // белые глаза-точки
        for (int side = -1; side <= 1; side += 2) {
            put(viewers, base, rx, rz, fx, fz, side * 0.15, 2.72, 0.36, WHITE);
        }
    }

    private void put(Set<Player> viewers, Location base, double rx, double rz, double fx, double fz,
                     double x, double y, double z, Particle.DustOptions dust) {
        if (y > cutY) return;
        double wx = base.getX() + rx * x + fx * z;
        double wy = base.getY() + y;
        double wz = base.getZ() + rz * x + fz * z;
        for (Player v : viewers) v.spawnParticle(Particle.DUST, wx, wy, wz, 1, 0, 0, 0, 0, dust);
    }

    private Vector unit() {
        Vector v;
        do {
            v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
        } while (v.lengthSquared() < 1e-6);
        return v.normalize();
    }
}
