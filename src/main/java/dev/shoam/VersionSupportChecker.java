package dev.shoam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

public class VersionSupportChecker {

    private static final String VERSION_SUPPORT_URL =
            "https://raw.githubusercontent.com/shamgamer/ShamPlugin/refs/heads/master/versions.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JavaPlugin plugin;
    private final HttpClient httpClient;

    public VersionSupportChecker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Writes the server implementation/version and every dependency declared in
     * plugin.yml to the console. This is informational only; it never prevents
     * ShamPlugin from enabling.
     */
    public void logStartupReport() {
        logStartupReport(false);
    }

    /**
     * @param debug whether to write detailed compatibility lookup diagnostics to the console
     */
    public void logStartupReport(boolean debug) {
        plugin.getLogger().info("Server version: " + Bukkit.getVersion());
        plugin.getLogger().info("Minecraft version: " + Bukkit.getVersion().split("-")[0]);
        plugin.getLogger().info("Bukkit API version: " + Bukkit.getBukkitVersion());

        PluginDescriptionFile description = plugin.getDescription();
        Set<String> requiredDependencies = new LinkedHashSet<>(description.getDepend());
        Set<String> optionalDependencies = new LinkedHashSet<>(description.getSoftDepend());

        logDependencies("Required dependency", requiredDependencies, true);
        logDependencies("Optional dependency", optionalDependencies, false);

        checkMinecraftVersionSupport(debug);
    }

    private void checkMinecraftVersionSupport(boolean debug) {
        String pluginVersion = plugin.getDescription().getVersion().split("\\s+", 2)[0];
        String minecraftVersion = Bukkit.getVersion().split("-")[0];
        debug(debug, "Starting compatibility lookup: plugin version '" + plugin.getDescription().getVersion()
                + "' -> '" + pluginVersion + "', Server version '" + Bukkit.getVersion()
                + "' -> Minecraft '" + minecraftVersion + "'.");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                debug(debug, "Requesting version support data from " + VERSION_SUPPORT_URL + ".");
                HttpRequest request = HttpRequest.newBuilder(URI.create(VERSION_SUPPORT_URL))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .header("User-Agent", "ShamPlugin-VersionSupportChecker")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                debug(debug, "Version support request completed with HTTP " + response.statusCode() + ".");

                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("Could not retrieve ShamPlugin version support data (HTTP "
                            + response.statusCode() + ").");
                    return;
                }

                reportCompatibility(JSON.readTree(response.body()), pluginVersion, minecraftVersion, debug);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not retrieve ShamPlugin version support data: " + e.getMessage());
                debug(debug, "Compatibility lookup failed with " + e.getClass().getSimpleName() + ".");
            }
        });
    }

    private void reportCompatibility(JsonNode versions, String pluginVersion, String minecraftVersion, boolean debug) {
        reportMinecraftSupport(versions.path("SupportedMinecraftVersions"), minecraftVersion, debug);

        JsonNode support = versions.path(pluginVersion);
        if (!support.isObject()) {
            debug(debug, "No versions.json entry exists for ShamPlugin " + pluginVersion + ".");
            warnUntested(pluginVersion, minecraftVersion);
            return;
        }

        if (containsVersion(support.path("broken"), minecraftVersion)) {
            debug(debug, "Minecraft " + minecraftVersion + " is listed under 'broken' for ShamPlugin " + pluginVersion + ".");
            plugin.getLogger().severe("ShamPlugin " + pluginVersion
                    + " is known to be BROKEN on Minecraft " + minecraftVersion + ".");
        } else if (!containsVersion(support.path("working"), minecraftVersion)) {
            debug(debug, "Minecraft " + minecraftVersion + " is in neither the 'working' nor 'broken' list for ShamPlugin "
                    + pluginVersion + ".");
            warnUntested(pluginVersion, minecraftVersion);
        } else {
            debug(debug, "Minecraft " + minecraftVersion + " is listed as working for ShamPlugin " + pluginVersion
                    + "; no compatibility warning will be sent.");
        }
        // A listed working version intentionally produces no compatibility message as there is no issue.
    }

    private void reportMinecraftSupport(JsonNode supportedVersions, String minecraftVersion, boolean debug) {
        if (containsVersion(supportedVersions, minecraftVersion)) {
            debug(debug, "Minecraft " + minecraftVersion + " is still receiving ShamPlugin updates.");
            return;
        }

        plugin.getLogger().warning("Minecraft " + minecraftVersion
                + " is no longer receiving ShamPlugin updates. Proceed with caution!");
        debug(debug, "Minecraft " + minecraftVersion + " is not listed in 'SupportedMinecraftVersions'.");
    }

    private void debug(boolean enabled, String message) {
        if (enabled) {
            plugin.getLogger().info("[Version Support Debug] " + message);
        }
    }

    private boolean containsVersion(JsonNode versions, String minecraftVersion) {
        if (!versions.isArray()) {
            return false;
        }

        for (JsonNode version : versions) {
            if (matchesVersionRule(version.asText(), minecraftVersion)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches an exact Minecraft version or one of the compact rules accepted by
     * versions.json:
     * <ul>
     *     <li>{@code X+}: X and every newer version</li>
     *     <li>{@code X-}: X and every older version</li>
     *     <li>{@code X~Y}: every version from X through Y, inclusive</li>
     * </ul>
     * Versions are compared component by component, rather than as decimal
     * numbers, so for example {@code 26.1.10} is newer than {@code 26.1.2}.
     */
    private boolean matchesVersionRule(String rule, String minecraftVersion) {
        String normalizedRule = rule.trim();

        if (normalizedRule.endsWith("+")) {
            String minimumVersion = normalizedRule.substring(0, normalizedRule.length() - 1);
            return isNumericVersion(minecraftVersion) && isNumericVersion(minimumVersion)
                    && compareVersions(minecraftVersion, minimumVersion) >= 0;
        }

        if (normalizedRule.endsWith("-")) {
            String maximumVersion = normalizedRule.substring(0, normalizedRule.length() - 1);
            return isNumericVersion(minecraftVersion) && isNumericVersion(maximumVersion)
                    && compareVersions(minecraftVersion, maximumVersion) <= 0;
        }

        int rangeSeparator = normalizedRule.indexOf('~');
        if (rangeSeparator >= 0 && rangeSeparator == normalizedRule.lastIndexOf('~')) {
            String minimumVersion = normalizedRule.substring(0, rangeSeparator);
            String maximumVersion = normalizedRule.substring(rangeSeparator + 1);
            return isNumericVersion(minecraftVersion) && isNumericVersion(minimumVersion) && isNumericVersion(maximumVersion)
                    && compareVersions(minecraftVersion, minimumVersion) >= 0
                    && compareVersions(minecraftVersion, maximumVersion) <= 0;
        }

        return minecraftVersion.equals(normalizedRule);
    }

    private boolean isNumericVersion(String version) {
        return compareVersions(version, version) != Integer.MIN_VALUE;
    }

    /**
     * @return a negative value when {@code first} is older, zero when the
     * versions are equivalent, a positive value when {@code first} is newer,
     * or {@link Integer#MIN_VALUE} if either version is not numeric.
     */
    private int compareVersions(String first, String second) {
        String[] firstParts = first.split("\\.", -1);
        String[] secondParts = second.split("\\.", -1);

        int componentCount = Math.max(firstParts.length, secondParts.length);
        for (int index = 0; index < componentCount; index++) {
            Integer firstPart = versionComponent(firstParts, index);
            Integer secondPart = versionComponent(secondParts, index);
            if (firstPart == null || secondPart == null) {
                return Integer.MIN_VALUE;
            }

            int comparison = Integer.compare(firstPart, secondPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private Integer versionComponent(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }

        try {
            return parts[index].isEmpty() ? null : Integer.parseInt(parts[index]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void warnUntested(String pluginVersion, String minecraftVersion) {
        plugin.getLogger().warning("ShamPlugin " + pluginVersion + " has not been tested on Minecraft "
                + minecraftVersion + ". Proceed with caution!");
    }

    private void logDependencies(String type, Set<String> dependencies, boolean required) {
        if (dependencies.isEmpty()) {
            plugin.getLogger().info(type + "s: none declared in plugin.yml.");
            return;
        }

        for (String dependencyName : dependencies) {
            Plugin dependency = Bukkit.getPluginManager().getPlugin(dependencyName);
            if (dependency == null) {
                String status = required ? "MISSING" : "not installed";
                plugin.getLogger().warning(type + " '" + dependencyName + "': " + status + ".");
                continue;
            }

            String status = dependency.isEnabled() ? "installed and enabled" : "installed but disabled";
            plugin.getLogger().info(type + " '" + dependencyName + "': " + status
                    + " (version " + dependency.getDescription().getVersion() + ").");
        }
    }
}
