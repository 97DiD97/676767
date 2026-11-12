package dev.eyesky;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.logging.Level;

/**
 * Лазер глаза. Четыре режима:
 *  HERE   - бить прямо вниз оттуда, где глаз висит;
 *  POINT  - подлететь к точке на земле и бить в неё;
 *  PLAYER - подлететь к игроку и бить в то место, где он стоял;
 *  TRACK  - подлететь к игроку и водить лучом за ним, выжигая дорожку;
 *  EXORCISM - как TRACK, но луч света, без урона и разрушений: изгоняет форму "тень";
 *  DUMMY  - подлететь к стоящей отдельно тени-кукле и изгнать её лучом света.
 *
 * Фазы: APPROACH (подлёт) -> CHARGE (отсчёт) -> FIRE (луч) -> FADE (затухание).
 * Всё работает одним таймером в основном потоке. Любая ошибка гасит лазер и пишется в чат/консоль.
 */
public final class Laser {

    public enum Mode { HERE, POINT, PLAYER, TRACK, EXORCISM, DUMMY }

    private enum Phase { APPROACH, CHARGE, FIRE, FADE }

    private static final String TAG = "eyesky_fx";      // метка эффектов (чистятся при старте плагина)
    private static final int FADE_TICKS = 50;
    private static final double SQUASH = 1.35;           // воронка - чаша, а не шар

    private final EyeSkyPlugin plugin;
    private final EyeManager eye;
    private final Mode mode;
    private final Player target;
    private final Location point;
    private final CommandSender sender;
    private final int dummyId;                // id тени-куклы (режим DUMMY), иначе -1
    private final Random rnd = new Random();

    // ---- настройки ----
    private final int chargeTicks, fireTicks;
    private final double radius, trackRadius, damage, beamWidth, liningThickness, holdSpeed;
    private final boolean breakBlocks, trackLike;
    private final List<Material> lining = new ArrayList<>();

    // ---- состояние ----
    private BukkitTask task;
    private World world;
    private Phase phase = Phase.APPROACH;
    private int t;
    private int totalTicks;
    private String fail = "неизвестная причина";

    private Vector eyePos = new Vector();
    private Location impact;                 // куда упирается луч (верхняя точка земли)
    private double holdY;                    // высота, на которой глаз держится в режиме TRACK
    private double craterDone;               // радиус уже выжженной воронки (режимы HERE/POINT/PLAYER)
    private int cx, cy, cz;                  // центр воронки (блок)

    private BlockDisplay core, mid, outer;
    private TextDisplay counter;
    private ItemDisplay eyeGlow, impactGlow;
    private final List<Entity> spawned = new ArrayList<>();

    public Laser(EyeSkyPlugin plugin, EyeManager eye, Mode mode, Player target, Location point,
                 int fireSeconds, CommandSender sender, int dummyId) {
        this.plugin = plugin;
        this.eye = eye;
        this.mode = mode;
        this.target = target;
        this.point = point;
        this.sender = sender;
        this.dummyId = dummyId;
        FileConfiguration c = plugin.getConfig();
        trackLike = mode == Mode.TRACK || mode == Mode.EXORCISM;
        int cs = mode == Mode.TRACK ? c.getInt("laser.track-charge-seconds", 3)
                : (mode == Mode.EXORCISM || mode == Mode.DUMMY) ? c.getInt("laser.exorcism-charge-seconds", 3)
                : c.getInt("laser.charge-seconds", 5);
        chargeTicks = Math.max(20, cs * 20);
        int fs = fireSeconds > 0 ? fireSeconds
                : mode == Mode.TRACK ? c.getInt("laser.track-seconds", 12)
                : (mode == Mode.EXORCISM || mode == Mode.DUMMY) ? c.getInt("shade.exorcism-seconds", 3)
                : c.getInt("laser.fire-seconds", 4);
        fireTicks = Math.max(20, fs * 20);
        radius = Math.max(2.0, c.getDouble("laser.radius", 7.0));
        trackRadius = Math.max(1.5, c.getDouble("laser.track-radius", 3.5));
        damage = (mode == Mode.EXORCISM || mode == Mode.DUMMY) ? 0.0 : c.getDouble("laser.damage", 10.0);
        beamWidth = Math.max(0.5, c.getDouble("laser.beam-width", 1.8));
        liningThickness = Math.max(0.5, c.getDouble("laser.lining-thickness", 3.0));
        holdSpeed = Math.max(0.3, c.getDouble("laser.track-speed", 2.0));
        breakBlocks = mode != Mode.EXORCISM && mode != Mode.DUMMY && c.getBoolean("laser.break-blocks", true);

        List<String> names = c.getStringList("laser.crater-blocks");
        if (names.isEmpty()) {
            names = List.of("BLACKSTONE", "BLACKSTONE", "MAGMA_BLOCK", "MAGMA_BLOCK", "COAL_BLOCK",
                    "SMOOTH_BASALT", "OBSIDIAN", "CRYING_OBSIDIAN", "NETHERRACK");
        }
        for (String n : names) {
            Material m = Material.matchMaterial(n.trim());
            if (m != null && m.isBlock()) lining.add(m);
            else plugin.getLogger().warning("laser.crater-blocks: неизвестный блок " + n);
        }
        if (lining.isEmpty()) lining.add(Material.BLACKSTONE);
    }

    // =====================================================================================
    //  Запуск и остановка
    // =====================================================================================

    public String failReason() { return fail; }

    public String phaseName() { return mode + "/" + phase + " t=" + t; }

    public boolean start() {
        world = eye.world();
        Vector p = eye.position();
        if (world == null || p == null) { fail = "нет мира или позиции глаза"; return false; }
        eyePos = p;
        if ((mode == Mode.PLAYER || trackLike) && (target == null || !target.isOnline())) {
            fail = "игрок не найден";
            return false;
        }
        eye.setHoldSpeed(holdSpeed);
        if (mode == Mode.HERE) {
            if (!computeImpact()) { fail = "луч вниз не нашёл земли под глазом"; return false; }
            beginCharge();
            t = 0;
        } else {
            phase = Phase.APPROACH;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        log("старт, режим " + mode);
        return true;
    }

    /** Досрочная остановка. */
    public void abort() {
        if (task != null) task.cancel();
        task = null;
        cleanup();
    }

    private void finish() {
        if (task != null) task.cancel();
        task = null;
        cleanup();
        eye.laserEnded(this);
    }

    private void cleanup() {
        for (Entity e : spawned) if (e != null) e.remove();
        spawned.clear();
        eye.setHold(null);
        eye.setHoldSpeed(0);
        eye.visual(1.0, 0.0);
    }

    private void tick() {
        try {
            if (++totalTicks > 400 + chargeTicks + fireTicks + FADE_TICKS + 200) {   // страховка от зависания
                log("таймаут, лазер остановлен");
                finish();
                return;
            }
            Vector p = eye.position();
            if (p == null || eye.world() != world) { finish(); return; }
            eyePos = p;
            switch (phase) {
                case APPROACH -> tickApproach();
                case CHARGE -> tickCharge();
                case FIRE -> tickFire();
                case FADE -> tickFade();
            }
            t++;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Ошибка в лазере глаза", ex);
            StackTraceElement[] st = ex.getStackTrace();
            tell("Ошибка лазера: " + ex + (st.length > 0 ? " в " + st[0] : ""));
            finish();
        }
    }

    private void log(String s) {
        plugin.getLogger().info("[лазер] " + s);
    }

    private void tell(String s) {
        if (sender != null) sender.sendMessage(Component.text(s, NamedTextColor.RED));
    }

    // =====================================================================================
    //  1. Подлёт
    // =====================================================================================

    private void tickApproach() {
        Location goal;
        if (mode == Mode.POINT || mode == Mode.DUMMY) {
            goal = point;
        } else {
            if (target == null || !target.isOnline() || !target.getWorld().equals(world)) { finish(); return; }
            goal = target.getLocation();
        }
        int surface = world.getHighestBlockYAt(goal);
        double y = Math.min(Math.max(eyePos.getY(), surface + 40), world.getMaxHeight() - 8);
        eye.setHold(new Vector(goal.getX(), y, goal.getZ()));
        eye.forceEmotionTicks(Emotion.NORMAL, 3);

        if (t == 160) {          // не долетел за 8 секунд (стены, незагруженные чанки) - переносится сам
            eye.teleportEye(new Vector(goal.getX(), y, goal.getZ()));
            log("глаз перенесён к цели");
        }
        double reach = (mode == Mode.POINT || mode == Mode.DUMMY) ? 2.0 : mode == Mode.PLAYER ? 2.5 : 6.0;
        double dx = goal.getX() - eyePos.getX(), dz = goal.getZ() - eyePos.getZ();
        if ((Math.hypot(dx, dz) < reach && Math.abs(y - eyePos.getY()) < 3) || t > 400) {
            holdY = y;
            if (trackLike) {
                if (!trackImpact()) { finish(); return; }
            } else if (!computeImpact()) {
                tell("Лазер: под глазом нет земли.");
                finish();
                return;
            }
            beginCharge();
        }
    }

    // =====================================================================================
    //  2. Зарядка с отсчётом
    // =====================================================================================

    private void beginCharge() {
        phase = Phase.CHARGE;
        log(String.format("заряд, глаз в %.0f %.0f %.0f", eyePos.getX(), eyePos.getY(), eyePos.getZ()));
        t = -1;                                           // tick() сразу прибавит единицу

        if (trackLike) {
            holdY = eyePos.getY();
            followTarget();
        } else if (mode == Mode.POINT || mode == Mode.DUMMY) {
            eye.setHold(new Vector(point.getX(), eyePos.getY(), point.getZ()));
        } else {
            eye.setHold(eyePos.clone());                  // зависает на месте
        }

        counter = world.spawn(at(eyePos.getX(), eyePos.getY() - eye.eyeSize() * 0.5 - 1.0, eyePos.getZ()),
                TextDisplay.class, d -> {
                    d.text(Component.text(String.valueOf((chargeTicks + 19) / 20), NamedTextColor.YELLOW));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    d.setSeeThrough(true);
                    d.setShadowed(true);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setViewRange(6f);
                    d.setPersistent(false);
                    d.setInvulnerable(true);
                    d.setGravity(false);
                    d.setTransformation(scaleOnly(10f));
                    d.addScoreboardTag(TAG);
                });
        spawned.add(counter);

        eyeGlow = glowDisplay(eyePos.getX(), eyePos.getY(), eyePos.getZ());
        eyeGlow.setTransformation(scaleOnly(0.01f));

        sound(eyeLoc(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 8f, 1.0f);
        sound(eyeLoc(), Sound.BLOCK_BEACON_POWER_SELECT, 8f, 0.6f);
    }

    private void tickCharge() {
        if (trackLike && !followTarget()) {
            tell("Лазер: игрок пропал.");
            finish();
            return;
        }
        if (t >= chargeTicks) { beginFire(); return; }
        double p = (double) t / chargeTicks;                       // 0..1
        int left = chargeTicks - t;
        boolean lastSecond = left <= 20;

        // --- глаз: эмоция, пульсация и дрожь
        eye.forceEmotionTicks((mode == Mode.EXORCISM || mode == Mode.DUMMY) ? Emotion.EXORCISM : lastSecond ? Emotion.SURPRISED : Emotion.ANGRY, 3);
        eye.visual(1.0 + 0.12 * p + 0.05 * Math.sin(t * (0.35 + p * 1.4)), 0.05 + 0.35 * p);

        // --- свечение вокруг зрачка
        double gs = eye.eyeSize() * (0.5 + 1.7 * p) * (1.0 + 0.08 * Math.sin(t * 0.9));
        setScale(eyeGlow, gs, eyeLoc());

        // --- отсчёт
        counter.teleport(at(eyePos.getX(), eyePos.getY() - eye.eyeSize() * 0.5 - 1.0, eyePos.getZ()));
        if (t % 20 == 0) {
            int sec = (left + 19) / 20;
            NamedTextColor col = sec <= 1 ? NamedTextColor.RED : sec <= 3 ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
            counter.text(Component.text(String.valueOf(sec), col));
            sound(eyeLoc(), Sound.BLOCK_NOTE_BLOCK_PLING, 6f, (float) (0.6 + 1.2 * p));
            sound(eyeLoc(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 6f, 0.7f);
        }
        if (lastSecond && t % 5 == 0) sound(eyeLoc(), Sound.BLOCK_NOTE_BLOCK_PLING, 6f, 2.0f);
        if (t % 10 == 0) {
            actionBar(Component.text("⚠ ЛАЗЕР ЧЕРЕЗ " + ((left + 19) / 20) + " ⚠", TextColor.color(0xFF3030)));
            if (!trackLike) computeImpact();
        }

        // --- частицы: энергия стягивается в глаз
        int n = 4 + (int) (26 * p);
        for (int i = 0; i < n; i++) {
            Vector u = randomUnit();
            double dist = 4 + rnd.nextDouble() * 5;
            world.spawnParticle(Particle.END_ROD, eyePos.getX() + u.getX() * dist, eyePos.getY() + u.getY() * dist,
                    eyePos.getZ() + u.getZ() * dist, 0, -u.getX(), -u.getY(), -u.getZ(), 0.35 + rnd.nextDouble() * 0.2, null, true);
        }
        if (t % 3 == 0) {
            for (int i = 0; i < 3; i++) {
                Vector u = randomUnit();
                world.spawnParticle(Particle.ELECTRIC_SPARK, eyePos.getX() + u.getX() * 3, eyePos.getY() + u.getY() * 3,
                        eyePos.getZ() + u.getZ() * 3, 0, u.getX(), u.getY(), u.getZ(), 0.6, null, true);
            }
        }

        // --- предупреждение на земле: два кольца и тонкая линия к глазу
        double warnR = trackLike ? trackRadius : radius;
        if (t % 2 == 0) {
            boolean holyW = (mode == Mode.EXORCISM || mode == Mode.DUMMY);
            ring(warnR, 0.25, new Particle.DustOptions(holyW ? Color.fromRGB(255, 255, 255) : Color.fromRGB(255, 40, 20), 2.2f), 56);
            ring(warnR * (1.0 - p), 0.25, new Particle.DustOptions(holyW ? Color.fromRGB(255, 215, 80) : Color.fromRGB(255, 200, 40), 1.6f), 40);
        }
        if (t % 4 == 0) {
            Vector a = impact.toVector(), b = eyePos;
            double len = a.distance(b);
            for (double d = 0; d < len; d += 2.0) {
                Vector q = a.clone().add(b.clone().subtract(a).multiply(d / len));
                world.spawnParticle(Particle.DUST, q.getX(), q.getY(), q.getZ(), 1, 0.1, 0.1, 0.1, 0,
                        new Particle.DustOptions(Color.fromRGB(255, 120, 30), 1.4f), true);
            }
        }
    }

    // =====================================================================================
    //  3. Выстрел
    // =====================================================================================

    private void beginFire() {
        phase = Phase.FIRE;
        log("выстрел");
        t = -1;
        if (trackLike) trackImpact(); else computeImpact();
        setCenter();
        craterDone = 0;

        boolean holy = (mode == Mode.EXORCISM || mode == Mode.DUMMY);
        core = beamBlock(Material.WHITE_CONCRETE);
        mid = beamBlock(holy ? Material.WHITE_STAINED_GLASS : Material.YELLOW_STAINED_GLASS);
        outer = beamBlock(holy ? Material.YELLOW_STAINED_GLASS : Material.RED_STAINED_GLASS);
        if (holy && target != null) eye.exorcised(target);          // тень изгнана
        if (holy && dummyId >= 0) eye.exorcisedDummy(dummyId);

        impactGlow = glowDisplay(impact.getX(), impact.getY() + 1.0, impact.getZ());
        impactGlow.setTransformation(scaleOnly(0.01f));

        Location eL = eyeLoc();
        sound(eL, Sound.ENTITY_WARDEN_SONIC_BOOM, 10f, 0.8f);
        sound(eL, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 10f, 0.6f);
        sound(impact, Sound.ENTITY_GENERIC_EXPLODE, 10f, 0.5f);
        sound(impact, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 8f, 0.6f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, impact, 3, 1.5, 0.5, 1.5, 0, null, true);
        world.spawnParticle(Particle.SONIC_BOOM, eL, 1, 0, 0, 0, 0, null, true);
        eye.visual(1.25, 0.0);
    }

    private void tickFire() {
        if (trackLike && !followTarget()) { beginFade(); return; }
        if (t >= fireTicks) { beginFade(); return; }
        double p = (double) t / fireTicks;
        boolean track = trackLike;

        eye.forceEmotionTicks((mode == Mode.EXORCISM || mode == Mode.DUMMY) ? Emotion.EXORCISM : Emotion.SURPRISED, 3);
        eye.visual(1.2 + 0.05 * Math.sin(t * 1.3), 0.12);

        // --- луч
        double grow = Math.min(1.0, (t + 1) / 4.0);
        double flick = 1.0 + 0.12 * Math.sin(t * 1.7) + (rnd.nextDouble() - 0.5) * 0.1;
        updateBeam(grow * flick);

        // --- свечения
        setScale(eyeGlow, eye.eyeSize() * 2.1 * (1.0 + 0.1 * Math.sin(t * 1.1)), eyeLoc());
        double base = track ? trackRadius : radius;
        setScale(impactGlow, base * 2.0 * Math.min(1.0, (t + 1) / 6.0) * (1.0 + 0.1 * Math.sin(t * 1.4)),
                impact.clone().add(0, 1.0, 0));

        // --- воронка
        double r;
        if (track) {
            r = trackRadius;
            setCenter();
            if (breakBlocks && t % 2 == 0) scorchGround(trackRadius);     // только обугливает землю, ничего не ломает
        } else {
            r = radius * (1.0 - Math.pow(1.0 - p, 2.2));              // быстро в начале, медленнее к концу
            if (breakBlocks) carve(craterDone, r);
            craterDone = Math.max(craterDone, r);
        }

        // --- урон
        if (t % 10 == 0) hurt(Math.max(r, 2.0));

        // --- эффекты вдоль луча и в точке попадания
        Vector a = impact.toVector(), b = eyePos;
        for (int i = 0; i < 14; i++) {
            Vector q = a.clone().add(b.clone().subtract(a).multiply(rnd.nextDouble()));
            double ang = rnd.nextDouble() * Math.PI * 2, rr = beamWidth * (0.6 + rnd.nextDouble() * 0.6);
            world.spawnParticle(Particle.ELECTRIC_SPARK, q.getX() + Math.cos(ang) * rr, q.getY(), q.getZ() + Math.sin(ang) * rr,
                    0, Math.cos(ang), -0.2, Math.sin(ang), 0.5, null, true);
        }
        for (int i = 0; i < 4; i++) {
            Vector q = a.clone().add(b.clone().subtract(a).multiply(rnd.nextDouble()));
            world.spawnParticle(Particle.END_ROD, q.getX(), q.getY(), q.getZ(), 0, rnd.nextGaussian() * 0.2, -1, rnd.nextGaussian() * 0.2, 1.2, null, true);
        }
        Location ig = impact.clone().add(0, 0.5, 0);
        world.spawnParticle(Particle.FLAME, ig, 18, r * 0.35 + 0.5, 0.3, r * 0.35 + 0.5, 0.25, null, true);
        world.spawnParticle(Particle.LAVA, ig, 6, r * 0.3 + 0.5, 0.3, r * 0.3 + 0.5, 0, null, true);
        world.spawnParticle(Particle.LARGE_SMOKE, ig, 8, r * 0.4 + 0.5, 0.4, r * 0.4 + 0.5, 0.12, null, true);
        if (rnd.nextInt(4) == 0) {
            Location e = ig.clone().add((rnd.nextDouble() - 0.5) * r * 1.6, 0, (rnd.nextDouble() - 0.5) * r * 1.6);
            world.spawnParticle(Particle.EXPLOSION, e, 1, 0, 0, 0, 0, null, true);
            sound(e, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.7f + rnd.nextFloat() * 0.4f);
        }
        if (t % 4 == 0) {
            double sw = (t % 24) / 24.0 * base * 1.3;
            ring(sw, 0.4, new Particle.DustOptions(Color.fromRGB(255, 160, 60), 2.5f), 50);
        }
        if (t % 8 == 0) sound(impact, Sound.BLOCK_BEACON_AMBIENT, 8f, 0.5f);
        if (t % 5 == 0) sound(eyeLoc(), Sound.ENTITY_BLAZE_SHOOT, 6f, 0.5f + rnd.nextFloat() * 0.3f);
        if (t % 10 == 0) {
            Component c = Component.text("☠ ЛАЗЕР ☠", TextColor.color(0xFF2020));
            if (track && target != null && target.isOnline()) target.sendActionBar(c);
            actionBar(c);
        }
    }

    // =====================================================================================
    //  4. Затухание
    // =====================================================================================

    private void beginFade() {
        phase = Phase.FADE;
        log("затухание");
        t = -1;
        if (breakBlocks) {
            if (trackLike) scorchGround(trackRadius); else lineShell(radius);
        }
        sound(impact, Sound.BLOCK_FIRE_EXTINGUISH, 8f, 0.6f);
        eye.forceEmotionTicks(Emotion.SLEEPY, 100);       // устал после выстрела
    }

    private void tickFade() {
        if (t >= FADE_TICKS) { finish(); return; }
        double p = (double) t / FADE_TICKS;
        double k = Math.max(0.0, 1.0 - t / 10.0);          // луч гаснет за 10 тиков
        if (core != null) {
            updateBeam(k);
            if (k <= 0) {
                core.remove(); mid.remove(); outer.remove();
                spawned.remove(core); spawned.remove(mid); spawned.remove(outer);
                core = null;
            }
        }
        eye.visual(1.0 + 0.2 * (1 - p), 0.0);
        setScale(eyeGlow, Math.max(0.01, eye.eyeSize() * 2.1 * (1 - p)), eyeLoc());
        setScale(impactGlow, Math.max(0.01, (trackLike ? trackRadius : radius) * 2.0 * (1 - p)),
                impact.clone().add(0, 1.0, 0));

        double rr = trackLike ? trackRadius : radius;
        for (int i = 0; i < 3; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = rnd.nextDouble() * rr * 0.9;
            Location l = impact.clone().add(Math.cos(a) * d, 0.3 - rnd.nextDouble() * 2.0, Math.sin(a) * d);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, l, 0, 0, 1, 0, 0.06, null, true);
        }
        if (t % 4 == 0) {
            Location l = impact.clone().add((rnd.nextDouble() - 0.5) * rr, 0.2, (rnd.nextDouble() - 0.5) * rr);
            world.spawnParticle(Particle.LAVA, l, 2, 0.3, 0.1, 0.3, 0, null, true);
        }
    }

    // =====================================================================================
    //  Луч (может быть наклонным - от точки попадания к глазу)
    // =====================================================================================

    private BlockDisplay beamBlock(Material m) {
        BlockDisplay d = world.spawn(impact.clone(), BlockDisplay.class, b -> {
            b.setBlock(m.createBlockData());
            b.setBrightness(new Display.Brightness(15, 15));
            b.setViewRange(8f);
            b.setShadowRadius(0f);
            b.setTeleportDuration(1);
            b.setPersistent(false);
            b.setInvulnerable(true);
            b.setGravity(false);
            b.addScoreboardTag(TAG);
        });
        spawned.add(d);
        return d;
    }

    private void updateBeam(double k) {
        setBeam(core, beamWidth * 0.35 * k);
        setBeam(mid, beamWidth * 0.65 * k);
        setBeam(outer, beamWidth * 1.0 * k);
    }

    private void setBeam(BlockDisplay d, double w) {
        if (d == null) return;
        float fw = (float) Math.max(0.01, w);
        double dx = eyePos.getX() - impact.getX(), dy = eyePos.getY() - impact.getY(), dz = eyePos.getZ() - impact.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-3) return;
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0f, 1f, 0f),
                new Vector3f((float) (dx / len), (float) (dy / len), (float) (dz / len)));
        Vector3f off = new Vector3f(-fw / 2f, 0f, -fw / 2f);
        q.transform(off);                                   // сдвиг, чтобы луч был по центру оси
        d.teleport(impact);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(new Transformation(off, q, new Vector3f(fw, (float) len, fw), new Quaternionf()));
    }

    // =====================================================================================
    //  Воронка
    // =====================================================================================

    private void setCenter() {
        cx = impact.getBlockX();
        cy = (int) Math.floor(impact.getY() - 0.01);        // блок, на который упал луч
        cz = impact.getBlockZ();
    }

    /** Выжигает слой между радиусами from и to вокруг центра (сплюснутый по вертикали - получается чаша). */
    private void carve(double from, double to) {
        if (to <= from) return;
        int R = (int) Math.ceil(to);
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int dy = -R; dy <= R; dy++) {
                    double sy = dy * SQUASH;
                    double d = Math.sqrt(dx * dx + dz * dz + sy * sy);
                    if (d > to || d <= from) continue;
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!burnable(b)) continue;
                    if (rnd.nextInt(7) == 0) {
                        world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.3, 0.3, 0.3, 0.05,
                                b.getBlockData(), true);
                    }
                    b.setType(Material.AIR, false);
                }
            }
        }
    }

    /**
     * Режим TRACK: обугливает верхний слой земли вокруг луча. Блоки не ломаются, а только заменяются
     * (земля превращается в чёрный камень, магму, уголь и т.д.), трава и цветы над ними сгорают.
     */
    private void scorchGround(double r) {
        int R = (int) Math.ceil(r);
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r) continue;
                if (rnd.nextDouble() > 0.95 - 0.45 * (d / r)) continue;        // к краю обугливается реже
                for (int dy = 2; dy >= -3; dy--) {
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material m = b.getType();
                    if (!burnable(b) || !m.isSolid() || !m.isOccluding() || lining.contains(m)) continue;
                    Block above = b.getRelative(0, 1, 0);
                    if (above.isLiquid()) break;
                    if (!(above.getType().isAir() || above.isPassable())) continue;   // это не поверхность
                    if (!above.getType().isAir()) {
                        if (above.getState() instanceof TileState) break;
                        above.setType(Material.AIR, false);                            // сгорела трава/цветок
                    }
                    b.setType(lining.get(rnd.nextInt(lining.size())), false);
                    if (rnd.nextInt(10) < 4) {                                        // иногда обугливается и второй слой
                        Block under = b.getRelative(0, -1, 0);
                        Material um = under.getType();
                        if (burnable(under) && um.isSolid() && um.isOccluding() && !lining.contains(um)) {
                            under.setType(lining.get(rnd.nextInt(lining.size())), false);
                        }
                    }
                    if (rnd.nextInt(5) == 0) {
                        world.spawnParticle(Particle.FLAME, b.getLocation().add(0.5, 1.1, 0.5), 2, 0.3, 0.1, 0.3, 0.02, null, true);
                        world.spawnParticle(Particle.LARGE_SMOKE, b.getLocation().add(0.5, 1.2, 0.5), 1, 0.2, 0.1, 0.2, 0.02, null, true);
                    }
                    break;
                }
            }
        }
    }

    /** Стенки и дно воронки заменяются обугленными блоками (список в config.yml). */
    private void lineShell(double r) {
        int R = (int) Math.ceil(r + liningThickness + 1);
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int dy = -R; dy <= 2; dy++) {
                    double sy = dy * SQUASH;
                    double d = Math.sqrt(dx * dx + dz * dz + sy * sy);
                    if (d <= r || d > r + liningThickness) continue;
                    double chance = 0.95 - 0.55 * ((d - r) / liningThickness);   // край получается рваным
                    if (rnd.nextDouble() > chance) continue;
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!burnable(b) || !b.getType().isSolid()) continue;
                    b.setType(lining.get(rnd.nextInt(lining.size())), false);
                }
            }
        }
    }

    private boolean burnable(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;
        if (m.getHardness() < 0) return false;           // бедрок, барьеры, командные блоки
        if (m == Material.WATER || m == Material.LAVA) return false;
        return !(b.getState() instanceof TileState);      // сундуки, таблички, спавнеры - не трогаем
    }

    // =====================================================================================
    //  Урон
    // =====================================================================================

    private void hurt(double r) {
        if (damage <= 0) return;
        double reach = Math.max(radius, trackRadius) + 4;
        Vector a = impact.toVector(), b = eyePos;
        for (Entity e : world.getNearbyEntities(impact, reach, 90, reach)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le instanceof Player pl && (pl.getGameMode() == GameMode.SPECTATOR || pl.getGameMode() == GameMode.CREATIVE)) continue;
            Vector c = le.getBoundingBox().getCenter();
            boolean inBeam = segDist(c, a, b) <= beamWidth * 0.5 + 0.8;
            Location l = le.getLocation();
            boolean inCrater = Math.hypot(l.getX() - impact.getX(), l.getZ() - impact.getZ()) <= r + 0.5
                    && Math.abs(l.getY() - impact.getY()) <= r;
            if (!inBeam && !inCrater) continue;
            le.damage(damage);
            le.setFireTicks(120);
            Vector push = new Vector(l.getX() - impact.getX(), 0, l.getZ() - impact.getZ());
            push = push.lengthSquared() < 1e-4 ? new Vector(0, 0, 0) : push.normalize().multiply(0.6);
            le.setVelocity(le.getVelocity().add(push.setY(0.5)));
        }
    }

    private static double segDist(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double l2 = ab.lengthSquared();
        double s = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, p.clone().subtract(a).dot(ab) / l2));
        return p.distance(a.clone().add(ab.multiply(s)));
    }

    // =====================================================================================
    //  Цель
    // =====================================================================================

    /** Режим TRACK: глаз держится над игроком, луч упирается в землю у его ног. false - игрока больше нет. */
    private boolean followTarget() {
        if (!trackImpact()) return false;
        Location l = target.getLocation();
        eye.setHold(new Vector(l.getX(), holdY, l.getZ()));
        return true;
    }

    private boolean trackImpact() {
        if (target == null || !target.isOnline() || target.isDead() || !target.getWorld().equals(world)) return false;
        Location l = target.getLocation();
        double gy = l.getY();
        RayTraceResult res = null;
        try {
            res = world.rayTraceBlocks(l.clone().add(0, 0.5, 0), new Vector(0, -1, 0), 64, FluidCollisionMode.NEVER, true);
        } catch (Throwable ignored) { }
        if (res != null && res.getHitPosition() != null) gy = res.getHitPosition().getY();
        impact = new Location(world, l.getX(), gy, l.getZ());
        return true;
    }

    /** Куда упадёт луч: прямо вниз от глаза (или от точки) до первого блока. */
    private boolean computeImpact() {
        double rx = point != null ? point.getX() : eyePos.getX();
        double rz = point != null ? point.getZ() : eyePos.getZ();
        Location from = new Location(world, rx, eyePos.getY(), rz);
        double maxDist = Math.max(5.0, eyePos.getY() - world.getMinHeight());
        RayTraceResult res = null;
        try {
            res = world.rayTraceBlocks(from, new Vector(0, -1, 0), maxDist, FluidCollisionMode.NEVER, true);
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "rayTraceBlocks не сработал, беру самый верхний блок", ex);
        }
        if (res != null && res.getHitPosition() != null) {
            impact = new Location(world, rx, res.getHitPosition().getY(), rz);
            return true;
        }
        int top = world.getHighestBlockYAt((int) Math.floor(rx), (int) Math.floor(rz));   // запасной вариант
        if (top <= world.getMinHeight()) return false;
        impact = new Location(world, rx, top + 1.0, rz);
        return true;
    }

    // =====================================================================================
    //  Вспомогательное
    // =====================================================================================

    private Location eyeLoc() {
        return new Location(world, eyePos.getX(), eyePos.getY(), eyePos.getZ());
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private static Transformation scaleOnly(float s) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    private void setScale(ItemDisplay d, double s, Location where) {
        if (d == null) return;
        d.teleport(where);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(scaleOnly((float) Math.max(0.01, s)));
    }

    private ItemDisplay glowDisplay(double x, double y, double z) {
        ItemDisplay d = world.spawn(at(x, y, z), ItemDisplay.class, g -> {
            ItemStack item = new ItemStack(Material.PAPER);
            NamespacedKey key = Objects.requireNonNull(NamespacedKey.fromString("eyesky:9"));   // кадр 10 листа - свечение
            item.editMeta(meta -> meta.setItemModel(key));
            g.setItemStack(item);
            g.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            g.setBillboard(Display.Billboard.CENTER);
            g.setBrightness(new Display.Brightness(15, 15));
            g.setViewRange(8f);
            g.setShadowRadius(0f);
            g.setTeleportDuration(1);
            g.setPersistent(false);
            g.setInvulnerable(true);
            g.setGravity(false);
            g.addScoreboardTag(TAG);
        });
        spawned.add(d);
        return d;
    }

    /** Кольцо частиц на земле. */
    private void ring(double r, double lift, Particle.DustOptions dust, int points) {
        if (r < 0.3 || impact == null) return;
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            world.spawnParticle(Particle.DUST, impact.getX() + Math.cos(a) * r, impact.getY() + lift, impact.getZ() + Math.sin(a) * r,
                    1, 0, 0, 0, 0, dust, true);
        }
    }

    private Vector randomUnit() {
        Vector v;
        do {
            v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
        } while (v.lengthSquared() < 1e-6);
        return v.normalize();
    }

    private void sound(Location l, Sound s, float volume, float pitch) {
        world.playSound(l, s, SoundCategory.AMBIENT, volume, pitch);
    }

    private void actionBar(Component c) {
        for (Player p : world.getPlayers()) {
            double dx = p.getLocation().getX() - eyePos.getX(), dz = p.getLocation().getZ() - eyePos.getZ();
            if (dx * dx + dz * dz < 150 * 150) p.sendActionBar(c);
        }
    }
}
