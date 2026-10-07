package com.ntaganira.heritier.iWarehouse.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Audit settings from application.yml (app.audit.*). */
@Component
public class AuditProperties {

    private final Set<String> maskedFields;

    public AuditProperties(@Value("${app.audit.masked-fields:password,token,secret}") String masked) {
        this.maskedFields = Arrays.stream(masked.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Lower-cased field names that are always masked. */
    public Set<String> maskedFields() {
        return maskedFields;
    }
}
