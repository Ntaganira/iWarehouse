package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Price changes (SDP 5): only real changes become lines; another person approves them within the Delegation of Authority
 * and they are written on the list, provided the old prices still hold.
 */
class PriceChangeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final PriceList retail = new PriceList();
    private final Product clear6 = product("CLR-6");
    private final Product clear8 = product("CLR-8");
    private final List<PriceChangeLine> saved = new ArrayList<>();
    private final List<PriceListItem> items = new ArrayList<>();
    private PriceChangeRepository repo;
    private PriceListService priceLists;
    private DelegationOfAuthority authority;
    private PriceChangeService service;

    @BeforeEach
    void setUp() {
        retail.setId(UUID.randomUUID());
        retail.setName("Retail");
        items.add(item(clear6, "27000"));
        items.add(item(clear8, "36000"));
        repo = mock(PriceChangeRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            PriceChange c = a.getArgument(0);
            c.setId(UUID.randomUUID());
            when(repo.lockById(c.getId())).thenReturn(Optional.of(c));
            when(repo.findDetailedById(c.getId())).thenReturn(Optional.of(c));
            return c;
        });
        PriceChangeLineRepository lineRepo = mock(PriceChangeLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            saved.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(lineRepo.findByChangeIdOrderByLineNo(any())).thenAnswer(a -> saved);
        priceLists = mock(PriceListService.class);
        when(priceLists.findById(retail.getId())).thenReturn(retail);
        PriceListItemRepository itemRepo = mock(PriceListItemRepository.class);
        when(itemRepo.findByPriceList_Id(retail.getId())).thenAnswer(a -> items);
        ProductRepository productRepo = mock(ProductRepository.class);
        when(productRepo.findById(clear6.getId())).thenReturn(Optional.of(clear6));
        when(productRepo.findById(clear8.getId())).thenReturn(Optional.of(clear8));
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.PRICE_CHANGE)).thenReturn("PCH-WH-2026-000001");
        authority = mock(DelegationOfAuthority.class);
        when(authority.required(eq(AuthorityDecision.PRICE_CHANGE), any())).thenReturn(AuthorityLevel.MD);
        service = new PriceChangeService(repo, lineRepo, priceLists, itemRepo, mock(ServicePriceRepository.class), productRepo,
                mock(ProcessingServiceRepository.class), numbers, authority, mock(SettingService.class), mock(Notifier.class), CLOCK);
        signIn(4L, "commercial");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyRealChangesAreAskedAndTheyApplyOnceAnotherPersonApproves() {
        Map<UUID, BigDecimal> prices = new LinkedHashMap<>();
        prices.put(clear6.getId(), new BigDecimal("28500"));             // changed
        prices.put(clear8.getId(), new BigDecimal("36000"));             // the same: no line
        assertThatThrownBy(() -> service.request(retail.getId(), prices, Map.of(), " "))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("priceChange.reason.required"));
        PriceChange change = service.request(retail.getId(), prices, Map.of(), "New supplier prices");
        assertThat(change.getStatus()).isEqualTo(PriceChangeStatus.PENDING);
        assertThat(change.getAuthorityLevel()).isEqualTo(AuthorityLevel.MD);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getOldPrice()).isEqualByComparingTo("27000");
        assertThat(saved.get(0).getNewPrice()).isEqualByComparingTo("28500");
        verify(priceLists, never()).applyApprovedPrices(any(), any(), any());

        assertThatThrownBy(() -> service.approve(change.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("priceChange.ownApproval"));
        signIn(9L, "owner");
        service.approve(change.getId(), "ok");
        verify(authority).require(AuthorityDecision.PRICE_CHANGE, BigDecimal.ZERO, "ok");
        verify(priceLists).applyApprovedPrices(retail.getId(), Map.of(clear6.getId(), new BigDecimal("28500")), Map.of());
        assertThat(change.getStatus()).isEqualTo(PriceChangeStatus.APPROVED);
    }

    @Test
    void nothingChangedAsksNothingAndAStaleChangeIsRefused() {
        assertThat(service.request(retail.getId(), Map.of(clear6.getId(), new BigDecimal("27000")), Map.of(), "same")).isNull();
        PriceChange change = service.request(retail.getId(), Map.of(clear6.getId(), new BigDecimal("28500")), Map.of(), "New supplier prices");
        items.get(0).setPricePerM2(new BigDecimal("29000"));             // someone changed it meanwhile
        signIn(9L, "owner");
        assertThatThrownBy(() -> service.approve(change.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("priceChange.stale"));
        verify(priceLists, never()).applyApprovedPrices(any(), any(), any());
    }

    private PriceListItem item(Product product, String price) {
        PriceListItem i = new PriceListItem();
        i.setPriceList(retail);
        i.setProduct(product);
        i.setPricePerM2(new BigDecimal(price));
        return i;
    }

    private static Product product(String code) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        return p;
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
