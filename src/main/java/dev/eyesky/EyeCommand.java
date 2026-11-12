package dev.eyesky;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EyeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("visit", "laser", "shoot", "track", "shade", "stop", "emotion", "summon", "pack", "status", "reload");
    private final EyeSkyPlugin plugin;

    public EyeCommand(EyeSkyPlugin plugin) {
        this.plugin = plugin;
    }

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        EyeManager eye = plugin.eye();
        if (args.length == 0) {
            msg(sender, "/eye <visit [игрок]|laser [игрок]|shoot|track <игрок>|shade [игрок]|stop|emotion <имя> [сек]|summon|pack|status|reload>", NamedTextColor.GRAY);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "visit" -> {
                Player target;
                if (args.length > 1) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else {
                    List<Player> list = eye.candidates();
                    target = list.isEmpty() ? null : list.get(new java.util.Random().nextInt(list.size()));
                }
                if (target == null || !eye.forceVisit(target)) {
                    msg(sender, "Не получилось: игрок не найден, не в мире глаза, или глаз ещё не появился.", NamedTextColor.RED);
                } else {
                    msg(sender, "Глаз летит к " + target.getName(), NamedTextColor.GREEN);
                }
            }
            case "laser", "shoot", "track" -> {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("laser") && args.length > 1 && args[1].equalsIgnoreCase("stop")) {
                    msg(sender, eye.stopLaser() ? "Лазер остановлен." : "Лазер сейчас не работает.", NamedTextColor.GREEN);
                    return true;
                }
                if (eye.laserBusy()) {
                    msg(sender, "Лазер уже работает. Остановить: /eye stop. Состояние: /eye status", NamedTextColor.RED);
                    return true;
                }
                boolean ok;
                String text;
                if (sub.equals("shoot")) {
                    if (!(sender instanceof Player p)) {
                        msg(sender, "Только для игроков: глаз бьёт туда, куда ты смотришь.", NamedTextColor.RED);
                        return true;
                    }
                    var hit = p.rayTraceBlocks(400);
                    if (hit == null || hit.getHitPosition() == null) {
                        msg(sender, "Смотри на землю или на блок (не дальше 400 блоков).", NamedTextColor.RED);
                        return true;
                    }
                    org.bukkit.util.Vector v = hit.getHitPosition();
                    org.bukkit.Location point = new org.bukkit.Location(p.getWorld(), v.getX(), v.getY(), v.getZ());
                    ok = eye.startLaser(Laser.Mode.POINT, null, point, 0, sender);
                    text = String.format("Глаз летит к точке %.0f %.0f %.0f и заряжает лазер!", v.getX(), v.getY(), v.getZ());
                } else if (sub.equals("track")) {
                    if (args.length < 2) {
                        msg(sender, "/eye track <игрок> [секунд] - луч будет ходить за игроком", NamedTextColor.GRAY);
                        return true;
                    }
                    Player at = Bukkit.getPlayerExact(args[1]);
                    if (at == null) {
                        msg(sender, "Игрок не найден.", NamedTextColor.RED);
                        return true;
                    }
                    int sec = 0;
                    if (args.length > 2) {
                        try { sec = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) { }
                    }
                    ok = eye.startLaser(Laser.Mode.TRACK, at, null, sec, sender);
                    text = "Глаз выходит на " + at.getName() + ": луч будет ходить за ним!";
                } else if (args.length > 1) {
                    Player at = Bukkit.getPlayerExact(args[1]);
                    if (at == null) {
                        msg(sender, "Игрок не найден.", NamedTextColor.RED);
                        return true;
                    }
                    ok = eye.startLaser(Laser.Mode.PLAYER, at, null, 0, sender);
                    text = "Глаз летит к " + at.getName() + " и заряжает лазер!";
                } else {
                    ok = eye.startLaser(Laser.Mode.HERE, null, null, 0, sender);
                    text = "Лазер заряжается!";
                }
                msg(sender, ok ? text : "Не получилось: " + eye.laserError(), ok ? NamedTextColor.GREEN : NamedTextColor.RED);
            }
            case "shade" -> {
                String a1 = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                ShadeManager sm = plugin.shade();
                if (a1.equals("spawn")) {
                    if (!(sender instanceof Player sp)) {
                        msg(sender, "Только для игроков.", NamedTextColor.RED);
                        return true;
                    }
                    var hit = sp.rayTraceBlocks(60);
                    org.bukkit.Location at = hit != null && hit.getHitPosition() != null
                            ? new org.bukkit.Location(sp.getWorld(), hit.getHitPosition().getX(), hit.getHitPosition().getY(), hit.getHitPosition().getZ())
                            : sp.getLocation();
                    int id = sm.spawnDummy(at, sp.getLocation().getYaw() + 180f);
                    msg(sender, "Тень №" + id + " стоит там, куда ты смотришь. Глаз заметит её сам, "
                            + "или сразу: /eye shade exorcise. Убрать: /eye shade remove", NamedTextColor.GREEN);
                } else if (a1.equals("remove")) {
                    msg(sender, "Убрано теней: " + sm.removeDummies(), NamedTextColor.GREEN);
                } else if (a1.equals("exorcise")) {
                    if (!(sender instanceof Player sp)) {
                        msg(sender, "Только для игроков.", NamedTextColor.RED);
                        return true;
                    }
                    ShadeManager.Dummy d = sm.nearestDummy(sp.getLocation());
                    if (d == null) {
                        msg(sender, "Нет теней рядом. Сначала: /eye shade spawn", NamedTextColor.RED);
                    } else if (!eye.startLaser(Laser.Mode.DUMMY, null, d.loc.clone(), 0, sender, d.id)) {
                        msg(sender, "Не получилось: " + eye.laserError(), NamedTextColor.RED);
                    } else {
                        msg(sender, "Глаз летит изгонять тень №" + d.id + "!", NamedTextColor.GREEN);
                    }
                } else {
                    Player p;
                    if (args.length > 1) {
                        p = Bukkit.getPlayerExact(args[1]);
                    } else {
                        p = sender instanceof Player self ? self : null;
                    }
                    if (p == null) {
                        msg(sender, "/eye shade [игрок] - стать тенью (повторно - вернуться); /eye shade spawn|exorcise|remove - тень-кукла",
                                NamedTextColor.GRAY);
                        return true;
                    }
                    boolean on = sm.toggle(p);
                    msg(sender, p.getName() + (on ? ": теперь тень. Для других игроков ты чёрная фигура из частиц."
                            : ": снова обычный игрок."), NamedTextColor.GREEN);
                }
            }
            case "stop" -> msg(sender, eye.stopLaser() ? "Лазер остановлен." : "Лазер сейчас не работает.", NamedTextColor.GREEN);
            case "emotion" -> {
                Emotion e = args.length > 1 ? Emotion.parse(args[1]) : null;
                if (e == null) {
                    msg(sender, "Эмоции: " + String.join(", ", emotionNames()), NamedTextColor.RED);
                    return true;
                }
                int sec = 10;
                if (args.length > 2) {
                    try { sec = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) { }
                }
                eye.forceEmotion(e, sec);
                msg(sender, "Эмоция " + e + " на " + sec + " сек.", NamedTextColor.GREEN);
            }
            case "summon" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Только для игроков.", NamedTextColor.RED);
                    return true;
                }
                msg(sender, eye.summon(p) ? "Глаз перенесён в небо над тобой." : "Ты не в мире глаза.",
                        NamedTextColor.GREEN);
            }
            case "pack" -> {
                int n = 0;
                for (Player p : Bukkit.getOnlinePlayers()) if (plugin.sendPack(p)) n++;
                msg(sender, "Ресурспак отправлен игрокам: " + n, NamedTextColor.GREEN);
            }
            case "status" -> {
                msg(sender, eye.describe(), NamedTextColor.AQUA);
                if (sender instanceof Player sp && plugin.shade().isShade(sp)) {
                    msg(sender, "Тень: " + eye.visibilityReport(sp), NamedTextColor.GRAY);
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                eye.reload();
                msg(sender, "Настройки глаза перечитаны. (Порт/адрес пака применяются только после рестарта.)",
                        NamedTextColor.GREEN);
            }
            default -> msg(sender, "Неизвестная подкоманда.", NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUB) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("emotion")) {
            for (String s : emotionNames()) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("shade")) {
            for (String x : List.of("spawn", "exorcise", "remove")) if (x.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(x);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("visit") || args[0].equalsIgnoreCase("laser") || args[0].equalsIgnoreCase("track"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }

    private static List<String> emotionNames() {
        List<String> l = new ArrayList<>();
        for (Emotion e : Emotion.values()) l.add(e.name().toLowerCase(Locale.ROOT));
        return l;
    }
}
