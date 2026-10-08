package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.ProductDto;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.TaxCategory;
import com.ntaganira.heritier.iWarehouse.enums.GlassType;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.TaxCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Adding and editing glass products (MD-01, TAX-01). */
class ProductServiceTest {

    private ProductRepository repo;
    private TaxCategoryRepository taxRepo;
    private StockUnitRepository unitRepo;
    private PurchaseOrderLineRepository orderLineRepo;
    private ProductService service;

    private final TaxCategory standard = tax("STANDARD", true);
    private final TaxCategory exempt = tax("EXEMPT", false);

    @BeforeEach
    void setUp() {
        repo = mock(ProductRepository.class);
        taxRepo = mock(TaxCategoryRepository.class);
        when(taxRepo.findById(standard.getId())).thenReturn(Optional.of(standard));
        when(taxRepo.findById(exempt.getId())).thenReturn(Optional.of(exempt));
        when(taxRepo.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of(standard));
        when(repo.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
        unitRepo = mock(StockUnitRepository.class);
        orderLineRepo = mock(PurchaseOrderLineRepository.class);
        service = new ProductService(repo, taxRepo, mock(SettingService.class), unitRepo, orderLineRepo);
    }

    @Test
    void blankCodeTakesTheSuggestedOneAndTheColourIsTidied() {
        Product product = service.create(dto(GlassType.TINTED, " bronze ", "6", null, standard));

        assertThat(product.getCode()).isEqualTo("TNT-BRONZE-6");
        assertThat(product.getVariant()).isEqualTo("Bronze");
        assertThat(product.getTaxCategory()).isSameAs(standard);
        assertThat(product.isEnabled()).isTrue();
    }

    @Test
    void suggestedCodeInUseGetsANumber() {
        when(repo.existsByCode("CLR-6")).thenReturn(true);
        when(repo.existsByCode("CLR-6-2")).thenReturn(true);

        Product product = service.create(dto(GlassType.CLEAR, null, "6", null, standard));

        assertThat(product.getCode()).isEqualTo("CLR-6-3");
    }

    @Test
    void sameTypeColourAndThicknessIsRefusedWhateverTheCase() {
        when(repo.existsIdentity(GlassType.TINTED, "Bronze", new BigDecimal("6"))).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto(GlassType.TINTED, "bronze", "6", null, standard)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("thicknessMm");
                    assertThat(e.getMessageKey()).isEqualTo("product.exists");
                });
        verify(repo, never()).save(any());
    }

    @Test
    void typedCodeMustBeFree() {
        when(repo.existsByCode("FLOAT6")).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto(GlassType.CLEAR, null, "6", "FLOAT6", standard)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("code"));
    }

    @Test
    void newProductNeedsAnActiveVatCategory() {
        assertThatThrownBy(() -> service.create(dto(GlassType.CLEAR, null, "6", null, exempt)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("taxCategoryId");
                    assertThat(e.getMessageKey()).isEqualTo("product.taxCategory.inactive");
                });
    }

    @Test
    void editKeepsAVatCategoryDeactivatedSinceButCannotPickOne() {
        Product product = existing("CLR-6", exempt);
        when(repo.findWithTaxCategoryById(product.getId())).thenReturn(Optional.of(product));

        service.update(product.getId(), dto(GlassType.CLEAR, null, "6", "CLR-6", exempt));
        assertThat(product.getTaxCategory()).isSameAs(exempt);

        Product other = existing("CLR-8", standard);
        when(repo.findWithTaxCategoryById(other.getId())).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.update(other.getId(), dto(GlassType.CLEAR, null, "8", "CLR-8", exempt)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void editChangesCodeReorderLevelAndNotesOnly() {
        Product product = existing("CLR-6", standard);
        when(repo.findWithTaxCategoryById(product.getId())).thenReturn(Optional.of(product));
        ProductDto dto = dto(GlassType.MIRROR, "Gold", "12", "FLOAT-6", standard);
        dto.setReorderLevelM2(new BigDecimal("150"));
        dto.setNotes("  Guardian  ");

        service.update(product.getId(), dto);

        assertThat(product.getCode()).isEqualTo("FLOAT-6");
        assertThat(product.getReorderLevelM2()).isEqualByComparingTo("150");
        assertThat(product.getNotes()).isEqualTo("Guardian");
        // what the glass is never changes on edit (the columns are also updatable = false)
        assertThat(product.getGlassType()).isEqualTo(GlassType.CLEAR);
        assertThat(product.getVariant()).isNull();
        assertThat(product.getThicknessMm()).isEqualByComparingTo("6");
    }

    @Test
    void editRefusesACodeAnotherProductUses() {
        Product product = existing("CLR-6", standard);
        when(repo.findWithTaxCategoryById(product.getId())).thenReturn(Optional.of(product));
        when(repo.existsByCodeAndIdNot(anyString(), any(UUID.class))).thenReturn(true);

        assertThatThrownBy(() -> service.update(product.getId(), dto(GlassType.CLEAR, null, "6", "CLR-8", standard)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("product.code.taken"));
    }

    @Test
    void formOffersActiveCategoriesPlusTheProductsOwnInactiveOne() {
        assertThat(service.taxCategoriesFor(null)).containsExactly(standard);
        assertThat(service.taxCategoriesFor(existing("CLR-6", exempt))).containsExactly(standard, exempt);
    }

    private static ProductDto dto(GlassType type, String variant, String thickness, String code, TaxCategory tax) {
        ProductDto dto = new ProductDto();
        dto.setGlassType(type);
        dto.setVariant(variant);
        dto.setThicknessMm(new BigDecimal(thickness));
        dto.setCode(code);
        dto.setTaxCategoryId(tax.getId());
        return dto;
    }

    private static Product existing(String code, TaxCategory tax) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setGlassType(GlassType.CLEAR);
        p.setThicknessMm(new BigDecimal("6"));
        p.setTaxCategory(tax);
        return p;
    }

    private static TaxCategory tax(String code, boolean enabled) {
        TaxCategory t = new TaxCategory();
        t.setId(UUID.randomUUID());
        t.setCode(code);
        t.setName(code);
        t.setRate(BigDecimal.ZERO);
        t.setEbmCode("A");
        t.setEnabled(enabled);
        return t;
    }

    @Test
    void aProductWithStockOrOpenOrdersStaysActive() {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setCode("CLR-6");
        when(repo.findWithTaxCategoryById(product.getId())).thenReturn(Optional.of(product));
        when(unitRepo.countByProduct_IdAndStatusIn(eq(product.getId()), any())).thenReturn(12L);
        assertThatThrownBy(() -> service.setEnabled(product.getId(), false)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("product.disable.stock"));
        when(unitRepo.countByProduct_IdAndStatusIn(eq(product.getId()), any())).thenReturn(0L);
        when(orderLineRepo.countByProduct_IdAndPurchaseOrder_StatusIn(eq(product.getId()), any())).thenReturn(2L);
        assertThatThrownBy(() -> service.setEnabled(product.getId(), false)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("product.disable.ordered"));
        when(orderLineRepo.countByProduct_IdAndPurchaseOrder_StatusIn(eq(product.getId()), any())).thenReturn(0L);
        assertThat(service.setEnabled(product.getId(), false).isEnabled()).isFalse();
    }
}
