package com.ntaganira.heritier.iWarehouse.config;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.text.Collator;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : Countries.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : ISO 3166-1 countries with names in the reader's language, from the JDK's own data
 *               (no list to maintain). Templates use it as ${@countries.name(code)}.
 * </pre>
 */
@Component("countries")
public class Countries {

    private static final Set<String> CODES = Set.of(Locale.getISOCountries());

    public record Country(String code, String name) {
    }

    public boolean isValid(String code) {
        return code != null && CODES.contains(code);
    }

    /** "China" / "Chine"; the code itself when unknown. */
    public String name(String code) {
        return name(code, LocaleContextHolder.getLocale());
    }

    public String name(String code, Locale language) {
        if (!isValid(code)) {
            return code == null ? "" : code;
        }
        String name = new Locale.Builder().setRegion(code).build().getDisplayCountry(language);
        return name.isBlank() ? code : name;
    }

    /** Every country, sorted by name in the reader's language. */
    public List<Country> options() {
        Locale language = LocaleContextHolder.getLocale();
        Collator collator = Collator.getInstance(language);
        List<Country> list = new ArrayList<>();
        for (String code : CODES) {
            list.add(new Country(code, name(code, language)));
        }
        list.sort(Comparator.comparing(Country::name, collator));
        return list;
    }
}
