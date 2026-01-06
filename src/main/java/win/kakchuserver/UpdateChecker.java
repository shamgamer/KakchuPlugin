package win.kakchuserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public final class UpdateChecker implements Runnable {
    private static final ObjectMapper JSON = new ObjectMapper();

    // Parses leading core versions like 2.5.6 or 3.0.0.1
    private static final Pattern CORE_VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private final Manager plugin;
    private final HttpClient http;

    public UpdateChecker(Manager plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void run() {
        var cfg = plugin.getConfig().getConfigurationSection("update-checker");
        if (cfg == null || !cfg.getBoolean("enabled", true)) return;

        String projectId = cfg.getString("project-id", "").trim();
        if (projectId.isEmpty()) return;

        boolean includePrerelease = cfg.getBoolean("include-prerelease", false);
        boolean notifyConsole = cfg.getBoolean("notify-console", true);
        boolean notifyOps = cfg.getBoolean("notify-ops", true);

        List<String> loaders = cfg.getStringList("loaders");
        String mcVersion = Bukkit.getMinecraftVersion(); // e.g. "1.21.11"
        String url = buildModrinthVersionsUrl(projectId, loaders, mcVersion);

        String current = plugin.getDescription().getVersion();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    // Modrinth wants a uniquely-identifying User-Agent
                    .header("User-Agent", "shamgamer/kakchuplugin/" + current)
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                if (notifyConsole) plugin.getLogger().warning("Update check failed: HTTP " + res.statusCode());
                // Don't clear cached update on transient HTTP failure
                return;
            }

            JsonNode arr = JSON.readTree(res.body());
            if (!arr.isArray() || arr.isEmpty()) {
                // Successful request but no usable data -> clear stale cached update
                plugin.clearUpdateAvailable();
                return;
            }

            // pick the newest acceptable version by date_published
            List<JsonNode> candidates = new ArrayList<>();
            for (JsonNode v : arr) {
                String type = v.path("version_type").asText("");
                if (!includePrerelease && !"release".equalsIgnoreCase(type)) continue;
                candidates.add(v);
            }
            if (candidates.isEmpty()) {
                plugin.clearUpdateAvailable();
                return;
            }

            JsonNode latest = candidates.stream()
                    .max(Comparator.comparing(a -> a.path("date_published").asText("")))
                    .orElse(null);
            if (latest == null) {
                plugin.clearUpdateAvailable();
                return;
            }

            String latestNumber = latest.path("version_number").asText("");
            if (latestNumber.isEmpty()) {
                plugin.clearUpdateAvailable();
                return;
            }

            // If up-to-date or newer, clear any previously cached update state.
            if (compareVersionLike(current, latestNumber) >= 0) {
                plugin.clearUpdateAvailable();
                return;
            }

            String projectPage = plugin.getDescription().getWebsite(); // from plugin.yml

            String msg = "Update available: " + latestNumber + " (installed: " + current + "). " +
                    (projectPage != null && !projectPage.isBlank() ? projectPage : "");

            // Cache for login notifications (safe off-thread: volatile fields)
            plugin.setUpdateAvailable(latestNumber, msg);

            // notify on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (notifyConsole) plugin.getLogger().warning(msg);

                if (notifyOps) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.isOp()) {
                            p.sendMessage("§e" + msg);
                        }
                    }
                }
            });

        } catch (Exception e) {
            if (notifyConsole) plugin.getLogger().warning("Update check failed: " + e.getMessage());
            // Don't clear cached update on transient exception
        }
    }

    private static String buildModrinthVersionsUrl(String projectId, List<String> loaders, String mcVersion) {
        String base = "https://api.modrinth.com/v2/project/" + enc(projectId) + "/version";

        List<String> qs = new ArrayList<>();

        if (loaders != null && !loaders.isEmpty()) {
            qs.add("loaders=" + enc(asJsonArray(loaders)));
        }
        if (mcVersion != null && !mcVersion.isBlank()) {
            qs.add("game_versions=" + enc(asJsonArray(List.of(mcVersion))));
        }

        if (qs.isEmpty()) return base;
        return base + "?" + String.join("&", qs);
    }

    private static String asJsonArray(List<String> items) {
        // ["paper","purpur"]
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (String s : items) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(s.replace("\"", "\\\"")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int compareVersionLike(String a, String b) {
        Version va = Version.parse(a);
        Version vb = Version.parse(b);
        return va.compareTo(vb);
    }

    private static final class Version implements Comparable<Version> {
        final int[] core;      // [major, minor, patch, extra]
        final PreRelease pre;  // null = release

        private Version(int[] core, PreRelease pre) {
            this.core = core;
            this.pre = pre;
        }

        static Version parse(String s) {
            if (s == null) return new Version(new int[]{0, 0, 0, 0}, null);

            String v = s.trim();

            // Strip trailing "(...)" (and optional preceding space or '-')
            v = v.replaceFirst("\\s*[- ]?\\(.*\\)\\s*$", "");
            // Remove spaces inside remaining string
            v = v.replace(" ", "");

            // Drop build metadata "+..."
            int plus = v.indexOf('+');
            if (plus >= 0) v = v.substring(0, plus);

            // Normalize "3.0.0B1" / "3.0.0beta1" / "3.0.0rc2" by inserting a dash
            v = v.replaceFirst("^(\\d+(?:\\.\\d+){2})(?=[A-Za-z])", "$1-");

            String[] parts = v.split("-", 2);

            int[] core = parseCore(parts[0]);
            PreRelease pre = null;

            if (parts.length == 2 && parts[1] != null && !parts[1].isBlank()) {
                pre = PreRelease.parse(parts[1]);
            }

            return new Version(core, pre);
        }

        private static int[] parseCore(String coreStr) {
            int[] out = {0, 0, 0, 0};
            if (coreStr == null) return out;

            var m = CORE_VERSION.matcher(coreStr);
            if (!m.find()) return out;

            for (int i = 1; i <= 4; i++) {
                String g = m.group(i);
                if (g != null) out[i - 1] = Integer.parseInt(g);
            }
            return out;
        }

        @Override
        public int compareTo(Version o) {
            for (int i = 0; i < 4; i++) {
                int diff = Integer.compare(this.core[i], o.core[i]);
                if (diff != 0) return diff;
            }

            // Same core: release > prerelease
            if (this.pre == null && o.pre == null) return 0;
            if (this.pre == null) return 1;
            if (o.pre == null) return -1;

            return this.pre.compareTo(o.pre);
        }
    }

    private static final class PreRelease implements Comparable<PreRelease> {
        // rank: alpha(0) < beta(1) < rc(2) < unknown(3)
        final int rank;
        final int number;
        final String raw;

        private PreRelease(int rank, int number, String raw) {
            this.rank = rank;
            this.number = number;
            this.raw = raw;
        }

        static PreRelease parse(String s) {
            String raw = (s == null) ? "" : s.trim();
            String lower = raw.replace('_', '.').toLowerCase();

            String label;
            String numPart = null;

            // Split "beta.1" or "beta-1"
            String[] parts = lower.split("[\\.-]", 2);
            label = parts[0];

            if (parts.length == 2) {
                numPart = parts[1];
            } else {
                // "beta1" / "b1" / "rc2"
                int idx = firstDigitIndex(label);
                if (idx >= 0) {
                    numPart = label.substring(idx);
                    label = label.substring(0, idx);
                }
            }

            int rank = switch (label) {
                case "a", "alpha" -> 0;
                case "b", "beta" -> 1;
                case "rc" -> 2;
                default -> 3;
            };

            int n = 0;
            if (numPart != null && !numPart.isBlank()) {
                try {
                    n = Integer.parseInt(numPart);
                } catch (NumberFormatException ignored) {
                    n = 0;
                }
            }

            return new PreRelease(rank, n, lower);
        }

        private static int firstDigitIndex(String s) {
            for (int i = 0; i < s.length(); i++) {
                if (Character.isDigit(s.charAt(i))) return i;
            }
            return -1;
        }

        @Override
        public int compareTo(PreRelease o) {
            // alpha < beta < rc < unknown
            int diff = Integer.compare(this.rank, o.rank);
            if (diff != 0) return diff;

            diff = Integer.compare(this.number, o.number);
            if (diff != 0) return diff;

            return this.raw.compareTo(o.raw);
        }
    }
}