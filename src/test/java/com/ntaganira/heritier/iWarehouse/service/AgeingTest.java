package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ageing of a customer's account (ACC-09): payments settle the oldest charges first, each charge is due its date plus
 * the payment terms, and what is left falls in its bucket by days past due.
 */
class AgeingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    @Test
    void paymentsSettleTheOldestChargesAndTheRestIsAgedFromItsDueDate() {
        List<Ageing.Entry> entries = List.of(
                charge("2026-06-01", "100000"),                                    // due 01/07: 100 days past due
                charge("2026-08-15", "50000"),                                     // due 14/09: 25 days
                credit("2026-09-01", "60000"),                                     // settles the first, 40,000 left of it
                charge("2026-10-01", "30000"));                                    // due 31/10: not due yet
        Ageing.Result r = Ageing.of(entries, 30, TODAY);

        assertThat(r.get(Ageing.Bucket.OVER_90)).isEqualByComparingTo("40000");
        assertThat(r.get(Ageing.Bucket.DAYS_1_30)).isEqualByComparingTo("50000");
        assertThat(r.get(Ageing.Bucket.NOT_DUE)).isEqualByComparingTo("30000");
        assertThat(r.get(Ageing.Bucket.DAYS_31_60)).isEqualByComparingTo("0");
        assertThat(r.balance()).isEqualByComparingTo("120000");
        assertThat(r.getOverdue()).isEqualByComparingTo("90000");
        assertThat(r.oldestDue()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void creditWithNothingToSettleWaitsForTheNextCharges() {
        Ageing.Result r = Ageing.of(List.of(credit("2026-09-01", "20000"), charge("2026-09-20", "15000")), 0, TODAY);
        assertThat(r.unsettledCredit()).isEqualByComparingTo("5000");
        assertThat(r.balance()).isEqualByComparingTo("-5000");
        assertThat(r.buckets()).isEmpty();
        assertThat(r.oldestDue()).isNull();

        Ageing.Result more = Ageing.of(List.of(credit("2026-09-01", "20000"), charge("2026-09-20", "25000")), 0, TODAY);
        assertThat(more.get(Ageing.Bucket.DAYS_1_30)).isEqualByComparingTo("5000");          // 19 days past due
        assertThat(more.unsettledCredit()).isEqualByComparingTo("0");
    }

    @Test
    void theBoundariesOfEachBucket() {
        assertThat(bucketOf("2026-10-09")).isEqualTo(Ageing.Bucket.NOT_DUE);                 // due today
        assertThat(bucketOf("2026-10-08")).isEqualTo(Ageing.Bucket.DAYS_1_30);
        assertThat(bucketOf("2026-09-09")).isEqualTo(Ageing.Bucket.DAYS_1_30);               // 30 days
        assertThat(bucketOf("2026-09-08")).isEqualTo(Ageing.Bucket.DAYS_31_60);
        assertThat(bucketOf("2026-08-10")).isEqualTo(Ageing.Bucket.DAYS_31_60);              // 60 days
        assertThat(bucketOf("2026-07-11")).isEqualTo(Ageing.Bucket.DAYS_61_90);              // 90 days
        assertThat(bucketOf("2026-07-10")).isEqualTo(Ageing.Bucket.OVER_90);
    }

    private static Ageing.Bucket bucketOf(String chargedOn) {
        return Ageing.of(List.of(charge(chargedOn, "1")), 0, TODAY).buckets().keySet().iterator().next();
    }

    private static Ageing.Entry charge(String date, String amount) {
        return new Ageing.Entry(LocalDate.parse(date), new BigDecimal(amount), BigDecimal.ZERO);
    }

    private static Ageing.Entry credit(String date, String amount) {
        return new Ageing.Entry(LocalDate.parse(date), BigDecimal.ZERO, new BigDecimal(amount));
    }
}
