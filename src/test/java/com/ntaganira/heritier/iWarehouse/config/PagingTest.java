package com.ntaganira.heritier.iWarehouse.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PagingTest {

    private static List<Integer> numbers(int n) {
        return IntStream.rangeClosed(1, n).boxed().toList();
    }

    @Test
    void firstPageHoldsOnePageSizeOfRows() {
        Page<Integer> page = Paging.of(numbers(45), 0);
        assertThat(page.getContent()).hasSize(Paging.SIZE).startsWith(1);
        assertThat(page.getTotalElements()).isEqualTo(45);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.isFirst()).isTrue();
    }

    @Test
    void lastPageHoldsTheRest() {
        Page<Integer> page = Paging.of(numbers(45), 2);
        assertThat(page.getContent()).containsExactly(41, 42, 43, 44, 45);
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.isLast()).isTrue();
    }

    @Test
    void aPagePastTheEndShowsTheLastOne() {
        Page<Integer> page = Paging.of(numbers(45), 9);
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getContent()).startsWith(41);
    }

    @Test
    void aNegativePageShowsTheFirstOne() {
        assertThat(Paging.of(numbers(5), -3).getNumber()).isZero();
        assertThat(Paging.page(-1)).isZero();
    }

    @Test
    void anEmptyListIsOneEmptyPage() {
        Page<Integer> page = Paging.of(List.of(), 4);
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getNumber()).isZero();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void onlyTheOpenTabKeepsItsPage() {
        assertThat(Paging.pageOf("units", "units", 3)).isEqualTo(3);
        assertThat(Paging.pageOf("units", "history", 3)).isZero();
    }
}
