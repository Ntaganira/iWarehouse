package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An alert reaches the holders of a permission named by its code ({@code notifier.holders("APPROVE_ADJUSTMENT", ...)}), and
 * permissions exist only as rows seeded by the migrations: a code no migration seeds tells nobody, silently. Every code
 * the services name is seeded.
 */
class NotifierPermissionsTest {

    private static final Pattern HOLDERS = Pattern.compile("notifier\\.holders\\(\\s*\"([A-Z0-9_]+)\"");
    /** The whole statement: a ';' inside a quoted description does not end it. */
    private static final Pattern PERMISSION_INSERT = Pattern.compile("INSERT INTO permissions(?:'(?:[^']|'')*'|[^';])*;",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_VALUE = Pattern.compile("\\(\\s*'([A-Z0-9_]+)'");

    @Test
    void everyPermissionAnAlertNamesIsSeeded() throws IOException {
        Set<String> named = codes(Path.of("src/main/java"), ".java", HOLDERS);
        Set<String> seeded = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                Matcher insert = PERMISSION_INSERT.matcher(Files.readString(file));
                while (insert.find()) {
                    Matcher value = FIRST_VALUE.matcher(insert.group());
                    while (value.find()) {
                        seeded.add(value.group(1));
                    }
                }
            }
        }

        assertThat(named).as("permissions named by notifier.holders").isNotEmpty();
        assertThat(seeded).as("permissions seeded by the migrations").containsAll(named);
    }

    private static Set<String> codes(Path root, String suffix, Pattern pattern) throws IOException {
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(suffix)).toList()) {
                Matcher m = pattern.matcher(Files.readString(file));
                while (m.find()) {
                    codes.add(m.group(1));
                }
            }
        }
        return codes;
    }
}
