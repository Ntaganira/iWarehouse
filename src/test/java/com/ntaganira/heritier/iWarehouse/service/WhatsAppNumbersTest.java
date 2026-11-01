package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The international form the WhatsApp gateway is given (TASK.md). */
class WhatsAppNumbersTest {

    @Test
    void rwandanNumbersTakeTheCountryCodeWhateverTheyLookLike() {
        assertThat(WhatsAppNumbers.international("+250 788 533 669")).isEqualTo("250788533669");
        assertThat(WhatsAppNumbers.international("0788533669")).isEqualTo("250788533669");
        assertThat(WhatsAppNumbers.international("00250788533669")).isEqualTo("250788533669");
    }

    @Test
    void anotherCountryKeepsItsCodeAndNumbersWithoutOneAreNotGuessed() {
        assertThat(WhatsAppNumbers.international("+254 712 345 678")).isEqualTo("254712345678");
        assertThat(WhatsAppNumbers.international("788533669")).isEqualTo("250788533669");
        assertThat(WhatsAppNumbers.international(" ")).isNull();
        assertThat(WhatsAppNumbers.international(null)).isNull();
    }
}
