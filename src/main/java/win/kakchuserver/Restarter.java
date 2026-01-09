package win.kakchuserver;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;


public class Restarter implements Runnable {
    private final JavaPlugin plugin;
    private LocalDate lastScheduleLogDate = null;
    private List<String> lastLoadedTimes = Collections.emptyList();

    // Fired warnings: yyyy-MM-dd|HH:mm|warnKey
    private final Set<String> firedWarnings = Collections.synchronizedSet(new HashSet<>());

    // Restart bookkeeping:
    // lastRestartTime - set when a restart was performed successfully
    private LocalDateTime lastRestartTime = null;
    // attemptedRestarts - track attempted restarts (successful or not) for a given scheduled time (yyyy-MM-dd|HH:mm)
    private final Set<String> attemptedRestarts = Collections.synchronizedSet(new HashSet<>());

    public Restarter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        final int nowHour = now.getHour();
        final int nowMinute = now.getMinute();
        final LocalDate today = now.toLocalDate();

        String day = now.getDayOfWeek().name().toLowerCase(Locale.ROOT);
        String basePath = plugin.getConfig().isConfigurationSection("restarter") ? "restarter" : plugin.getConfig().isConfigurationSection("discord.restarter") ? "discord.restarter" : "restarter";
        String schedulePath = basePath + ".schedule." + day;
        List<String> times = plugin.getConfig().getStringList(schedulePath);

        if (times.isEmpty()) {
            if (!today.equals(lastScheduleLogDate)) {
                plugin.getLogger().fine("Restarter: no schedule entries for '" + day + "' (checked path: " + schedulePath + ").");
                lastScheduleLogDate = today;
                lastLoadedTimes = Collections.emptyList();
            }
            return;
        }

        if (!today.equals(lastScheduleLogDate) || !Objects.equals(times, lastLoadedTimes)) {
            plugin.getLogger().info("Restarter: schedule for " + day + " (loaded from " + basePath + "): " + times);
            lastScheduleLogDate = today;
            lastLoadedTimes = new ArrayList<>(times);
            cleanOldEntries(today);
        }

        ConfigurationSection warningsSection = plugin.getConfig().getConfigurationSection(basePath + ".warnings");
        ConfigurationSection restartSection = plugin.getConfig().getConfigurationSection(basePath + ".restart");

        for (String timeStrRaw : times) {
            if (timeStrRaw == null) continue;
            String timeStr = timeStrRaw.trim();
            if (timeStr.isEmpty()) continue;

            String[] parts = timeStr.split(":");
            if (parts.length != 2) {
                plugin.getLogger().warning("Restarter: bad time format in schedule: " + timeStr);
                continue;
            }

            int tHour;
            int tMinute;
            try {
                tHour = Integer.parseInt(parts[0].trim());
                tMinute = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Invalid time in restarter schedule: " + timeStr);
                continue;
            }

            // scheduled restart instant (seconds = 0)
            LocalDateTime restartTime = LocalDateTime.of(
                    now.getYear(), now.getMonth(), now.getDayOfMonth(),
                    tHour, tMinute, 0
            );

            // === Warnings ===
            if (warningsSection != null) {
                for (String key : warningsSection.getKeys(false)) {
                    ConfigurationSection warnCfg = warningsSection.getConfigurationSection(key);
                    if (warnCfg == null) continue;

                    String message = warnCfg.getString("message");
                    String soundStr = warnCfg.getString("sound");
                    if (soundStr != null) soundStr = soundStr.trim();

                    long secondsBefore = parseWarningToSeconds(key);
                    if (secondsBefore <= 0) continue;

                    LocalDateTime warnTime = restartTime.minusSeconds(secondsBefore);

                    // compute how many seconds have passed since warnTime (positive if now >= warnTime)
                    long diffSec = Duration.between(warnTime, now).getSeconds();

                    // Fire only at or after warnTime and within tolerance (0..2 seconds after)
                    if (diffSec >= 0 && diffSec <= 2) {
                        String firedKey = buildWarningFiredKey(restartTime, key);
                        if (!firedWarnings.contains(firedKey)) {
                            plugin.getLogger().fine("Restarter: firing warning '" + key + "' for schedule " + timeStr + " (warnTime=" + warnTime + ", now=" + now + ")");
                            broadcastMessageAndSound(message, soundStr);
                            firedWarnings.add(firedKey);
                        } else {
                            plugin.getLogger().fine("Restarter: skipping already-fired warning '" + key + "' for " + restartTime);
                        }
                    }
                }
            }

            // === Restart ===
            if (restartSection != null) {
                if (nowHour == restartTime.getHour() && nowMinute == restartTime.getMinute()) {
                    String restartAttemptKey = buildAttemptKey(restartTime);

                    // If we've already successfully restarted at this scheduled time, skip.
                    if (lastRestartTime != null && lastRestartTime.equals(restartTime)) {
                        plugin.getLogger().fine("Restarter: restart for " + restartTime + " already executed, skipping.");
                        continue;
                    }

                    // If we've already attempted this scheduled restart (this minute), skip trying again.
                    if (attemptedRestarts.contains(restartAttemptKey)) {
                        plugin.getLogger().fine("Restarter: already attempted restart for " + restartTime + " this minute, skipping.");
                        continue;
                    }

                    // Broadcast notification & play sound
                    String message = restartSection.getString("message");
                    String soundStr = restartSection.getString("sound");
                    if (soundStr != null) soundStr = soundStr.trim();
                    broadcastMessageAndSound(message, soundStr);

                    // Mark we've attempted this scheduled restart (so we don't spam every tick)
                    attemptedRestarts.add(restartAttemptKey);

                    // Debug
                    plugin.getLogger().info("Attempting dispatch of restart command (scheduled time " + restartTime + ") ...");

                    // Read configured command (fallback to "stop" for compatibility)
                    String configuredCmd = restartSection.getString("command", "stop").trim();

                    try {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                boolean result = false;
                                try {
                                    result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), configuredCmd);
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Exception while dispatching restart command '" + configuredCmd + "': " + e.getMessage());
                                }

                                plugin.getLogger().info("Restart command '" + configuredCmd + "' dispatched: result=" + result);

                                // If configured command failed and wasn't "stop", try "stop" as a fallback once.
                                if (!result && !"stop".equalsIgnoreCase(configuredCmd)) {
                                    try {
                                        boolean fb = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop");
                                        plugin.getLogger().info("Fallback 'stop' dispatched: result=" + fb);
                                        if (fb) {
                                            // record successful restart via fallback
                                            lastRestartTime = restartTime;
                                        }
                                    } catch (Exception ex) {
                                        plugin.getLogger().warning("Exception dispatching fallback 'stop': " + ex.getMessage());
                                    }
                                } else if (result) {
                                    // record successful restart
                                    lastRestartTime = restartTime;
                                } else {
                                    // result==false and configuredCmd was "stop" (we already tried the default)
                                    plugin.getLogger().warning("Restart dispatch returned false for command '" + configuredCmd + "'. Ensure the command exists or configure 'restarter.restart.command' in config.");
                                }
                            }
                        }.runTask(plugin);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to schedule restart dispatch on main thread: " + e.getMessage() + " — attempting direct dispatch.");
                        try {
                            boolean result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), configuredCmd);
                            plugin.getLogger().info("Fallback direct dispatch of '" + configuredCmd + "': result=" + result);
                            if (!result && !"stop".equalsIgnoreCase(configuredCmd)) {
                                boolean fb = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop");
                                plugin.getLogger().info("Fallback direct 'stop' dispatch: result=" + fb);
                                if (fb) lastRestartTime = restartTime;
                            } else if (result) {
                                lastRestartTime = restartTime;
                            } else {
                                plugin.getLogger().warning("Direct dispatch returned false for '" + configuredCmd + "'.");
                            }
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Exception during fallback direct dispatch: " + ex.getMessage());
                        }
                    }
                }
            }
        }
    }

    private void cleanOldEntries(LocalDate today) {
        // Remove fired warnings not for today
        synchronized (firedWarnings) {
            firedWarnings.removeIf(k -> {
                try {
                    String datePart = k.split("\\|", 2)[0];
                    LocalDate entryDate = LocalDate.parse(datePart);
                    return !entryDate.equals(today);
                } catch (Exception e) {
                    return true;
                }
            });
        }
        // Remove attempted restart entries not for today
        synchronized (attemptedRestarts) {
            attemptedRestarts.removeIf(k -> {
                try {
                    String datePart = k.split("\\|", 2)[0];
                    LocalDate entryDate = LocalDate.parse(datePart);
                    return !entryDate.equals(today);
                } catch (Exception e) {
                    return true;
                }
            });
        }
    }

    private String buildWarningFiredKey(LocalDateTime restartTime, String warningKey) {
        String date = restartTime.toLocalDate().toString(); // yyyy-MM-dd
        String hm = String.format("%02d:%02d", restartTime.getHour(), restartTime.getMinute());
        return date + "|" + hm + "|" + warningKey;
    }

    private String buildAttemptKey(LocalDateTime restartTime) {
        String date = restartTime.toLocalDate().toString();
        String hm = String.format("%02d:%02d", restartTime.getHour(), restartTime.getMinute());
        return date + "|" + hm;
    }

    private long parseWarningToSeconds(String key) {
        return switch (key) {
            case "1h" -> 3600L;
            case "30m" -> 1800L;
            case "10m" -> 600L;
            case "5m" -> 300L;
            case "1m" -> 60L;
            case "30s" -> 30L;
            case "10s" -> 10L;
            case "5s" -> 5L;
            case "4s" -> 4L;
            case "3s" -> 3L;
            case "2s" -> 2L;
            case "1s" -> 1L;
            default -> 0L;
        };
    }

    private void broadcastMessageAndSound(String message, String soundStr) {
        if (message != null && !message.isEmpty()) {
            try {
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to broadcast restarter message: " + e.getMessage());
            }
        }

        if (soundStr == null || soundStr.isEmpty()) return;

        try {
            Sound sound = resolveSound(soundStr);
            if (sound == null) {
                plugin.getLogger().warning("Invalid sound configured for restarter: " + soundStr);
                return;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    player.playSound(player.getLocation(), sound, 1f, 1f);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to play restarter sound for player " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error while attempting to play restarter sound '" + soundStr + "': " + e.getMessage());
        }
    }

    private Sound resolveSound(String soundStr) {
        if (soundStr == null || soundStr.isBlank()) return null;
        String raw = soundStr.trim();

        try {
            NamespacedKey nk = NamespacedKey.fromString(raw, plugin);
            if (nk != null) {
                Sound found = Registry.SOUNDS.get(nk);
                if (found != null) return found;
            }
        } catch (IllegalArgumentException ignored) {
        } catch (NoSuchMethodError ignored) {
        }

        try {
            String candidate = raw.toLowerCase(Locale.ROOT).replace('_', '.').replace(' ', '.').replace(':', '.');
            if (!candidate.contains(":")) candidate = "minecraft:" + candidate;
            try {
                NamespacedKey nk2 = NamespacedKey.fromString(candidate, plugin);
                if (nk2 != null) {
                    Sound s2 = Registry.SOUNDS.get(nk2);
                    if (s2 != null) return s2;
                }
            } catch (IllegalArgumentException ignored) {
            } catch (NoSuchMethodError ignored) {
            }
        } catch (Exception ignored) {
        }

        String normInput = normalizeForMatch(raw);
        if (normInput.startsWith("minecraft")) normInput = normInput.substring("minecraft".length());

        Method registryGetKeyMethod = null;
        Method soundGetKeyMethod = null;
        try {
            registryGetKeyMethod = Registry.SOUNDS.getClass().getMethod("getKey", Object.class);
        } catch (NoSuchMethodException ignored) {
        } catch (NoSuchMethodError ignored) {
        }

        for (Sound s : Registry.SOUNDS) {
            if (s == null) continue;
            String candidateText = null;

            if (registryGetKeyMethod != null) {
                try {
                    Object keyObj = registryGetKeyMethod.invoke(Registry.SOUNDS, s);
                    if (keyObj != null) candidateText = keyObj.toString();
                } catch (IllegalAccessException | InvocationTargetException ignored) {
                }
            }

            if (candidateText == null) {
                if (soundGetKeyMethod == null) {
                    try {
                        soundGetKeyMethod = s.getClass().getMethod("getKey");
                    } catch (NoSuchMethodException ignored) {
                    } catch (NoSuchMethodError ignored) {
                    }
                }
                if (soundGetKeyMethod != null) {
                    try {
                        Object keyObj = soundGetKeyMethod.invoke(s);
                        if (keyObj != null) candidateText = keyObj.toString();
                    } catch (IllegalAccessException | InvocationTargetException ignored) {
                    }
                }
            }

            if (candidateText == null) candidateText = s.toString();
            if (candidateText == null) continue;
            String sNorm = normalizeForMatch(candidateText);
            if (sNorm.equals(normInput) || sNorm.endsWith(normInput) || normInput.endsWith(sNorm)) {
                return s;
            }
        }

        return null;
    }

    private String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}