package com.pointblue.dirxml.sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Renders a derived {@code case.properties} from an existing one — shared by the
 * features that generate runnable cases from a template ({@code harvest}) or a
 * variant of one ({@code compare}). It drops a set of keys, applies overrides,
 * rewrites on-disk config-source paths to absolute (so the generated case runs
 * from any directory), and backslash-escapes values so a slash-form DN
 * round-trips through {@code .properties} parsing.
 */
final class CaseProps {

    private CaseProps() {
    }

    /** Config-source / support keys whose value is a path, absolutized when on disk. */
    static final Set<String> PATH_KEYS = Set.of("export", "project", "ldifConfig", "schema", "ldif");

    /**
     * @param baseDir   directory the source props' relative paths resolve against
     * @param props     the source properties
     * @param drop      keys to omit entirely
     * @param overrides keys to force to a given value (emitted verbatim, escaped)
     * @param header    a leading comment line (without the leading {@code # })
     */
    static String render(Path baseDir, Properties props, Set<String> drop,
                         Map<String, String> overrides, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(header).append('\n');
        props.stringPropertyNames().stream().sorted().forEach(k -> {
            if (drop.contains(k) || overrides.containsKey(k)) {
                return;
            }
            sb.append(k).append('=').append(escape(resolveValue(baseDir, k, props.getProperty(k)))).append('\n');
        });
        overrides.forEach((k, v) -> sb.append(k).append('=').append(escape(v)).append('\n'));
        return sb.toString();
    }

    /** Absolutize a path-valued key when it points at something on disk; else verbatim. */
    private static String resolveValue(Path baseDir, String key, String value) {
        if (PATH_KEYS.contains(key) && value != null && !value.isBlank()) {
            Path resolved = baseDir.resolve(value.trim());
            if (Files.exists(resolved)) {
                return resolved.toAbsolutePath().normalize().toString();
            }
        }
        return value;
    }

    /** Re-escape backslashes so a slash-form DN survives a {@code .properties} round-trip. */
    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\");
    }
}
