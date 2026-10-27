package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.ProcessingServiceDto;
import com.ntaganira.heritier.iWarehouse.dto.ProductDto;
import com.ntaganira.heritier.iWarehouse.entity.PriceList;
import com.ntaganira.heritier.iWarehouse.entity.PriceListItem;
import com.ntaganira.heritier.iWarehouse.entity.ProcessingService;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.ServicePrice;
import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import com.ntaganira.heritier.iWarehouse.enums.ProcessingServiceKind;
import com.ntaganira.heritier.iWarehouse.repository.PriceListItemRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProcessingServiceRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.ServicePriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Excel import of products and accessories (MD-01, MD-06). */
class ProductImportServiceTest {

    private static final List<String> HEADERS = List.of(
            "Type", "Code", "Glass type", "Colour / finish / pattern", "Thickness (mm)",
            "Accessory name", "Accessory unit", "Sheet width (mm)", "Sheet height (mm)",
            "Sheet price (RWF)", "Unit price (RWF)", "Reorder level (m2)", "Max level (m2)", "Notes");

    private ProductService productService;
    private PriceListService priceListService;
    private ProductRepository productRepo;
    private ProcessingServiceRepository serviceRepo;
    private PriceListItemRepository itemRepo;
    private ServicePriceRepository servicePriceRepo;
    private ProductImportService importer;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        priceListService = mock(PriceListService.class);
        productRepo = mock(ProductRepository.class);
        serviceRepo = mock(ProcessingServiceRepository.class);
        itemRepo = mock(PriceListItemRepository.class);
        servicePriceRepo = mock(ServicePriceRepository.class);
        Messages messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));

        PriceList list = new PriceList();
        list.setId(UUID.randomUUID());
        when(priceListService.defaultList()).thenReturn(list);
        when(productService.defaultTaxCategoryId()).thenReturn(UUID.randomUUID());
        when(itemRepo.findByPriceList_IdAndProduct_Id(any(), any())).thenReturn(Optional.empty());
        when(servicePriceRepo.findByPriceList_IdAndService_Id(any(), any())).thenReturn(Optional.empty());

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        importer = new ProductImportService(productService, priceListService, productRepo, serviceRepo, itemRepo,
                servicePriceRepo, messages, transactionManager);
    }

    @Test
    void theLargestSheetOfAGlassSetsItsPricePerM2() {
        when(productService.create(any(ProductDto.class))).thenAnswer(i -> {
            ProductDto dto = i.getArgument(0);
            Product product = new Product();
            product.setId(UUID.randomUUID());
            product.setCode("CLR-6");
            product.setGlassType(dto.getGlassType());
            product.setThicknessMm(dto.getThicknessMm());
            return product;
        });
        when(itemRepo.save(any(PriceListItem.class))).thenAnswer(i -> i.getArgument(0));

        ProductImportService.Result result = importer.importFile(workbook(
                List.of("GLASS", "", "CLEAR", "", "6", "", "", "2440", "1830", "57000", "", "", "", ""),
                List.of("GLASS", "", "CLEAR", "", "6", "", "", "3210", "2250", "72000", "", "", "", "")));

        assertThat(result.glassCreated()).isEqualTo(1);
        assertThat(result.mergedGlassRows()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
        ArgumentCaptor<ProductDto> dto = ArgumentCaptor.forClass(ProductDto.class);
        verify(productService).create(dto.capture());
        assertThat(dto.getValue().getGlassType()).isEqualTo(com.ntaganira.heritier.iWarehouse.enums.GlassType.CLEAR);
        assertThat(dto.getValue().getThicknessMm()).isEqualByComparingTo("6");
        assertThat(dto.getValue().getTaxCategoryId()).isNotNull();
        ArgumentCaptor<PriceListItem> item = ArgumentCaptor.forClass(PriceListItem.class);
        verify(itemRepo).save(item.capture());
        assertThat(item.getValue().getPricePerM2()).isEqualByComparingTo("9968.85");
    }

    @Test
    void anAccessoryBecomesACatalogueServicePricedPerUnit() {
        when(serviceRepo.existsByNameIgnoreCase("Boop Brown Tape")).thenReturn(false);
        when(serviceRepo.existsByCode(anyString())).thenReturn(false);
        when(priceListService.createService(any(ProcessingServiceDto.class))).thenAnswer(i -> {
            ProcessingServiceDto dto = i.getArgument(0);
            ProcessingService service = new ProcessingService();
            service.setId(UUID.randomUUID());
            service.setCode(dto.getCode());
            service.setName(dto.getName());
            service.setKind(dto.getKind());
            service.setChargeUnit(dto.getChargeUnit());
            return service;
        });
        when(servicePriceRepo.save(any(ServicePrice.class))).thenAnswer(i -> i.getArgument(0));

        ProductImportService.Result result = importer.importFile(workbook(
                List.of("ACCESSORY", "", "", "", "", "Boop Brown Tape", "ROLL", "", "", "", "3500", "", "", "")));

        assertThat(result.accessoriesCreated()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
        ArgumentCaptor<ProcessingServiceDto> dto = ArgumentCaptor.forClass(ProcessingServiceDto.class);
        verify(priceListService).createService(dto.capture());
        assertThat(dto.getValue().getKind()).isEqualTo(ProcessingServiceKind.ACCESSORY);
        assertThat(dto.getValue().getChargeUnit()).isEqualTo(ChargeUnit.ROLL);
        assertThat(dto.getValue().getCode()).isEqualTo("BOOP-BROWN-TAPE");
        ArgumentCaptor<ServicePrice> price = ArgumentCaptor.forClass(ServicePrice.class);
        verify(servicePriceRepo).save(price.capture());
        assertThat(price.getValue().getPrice()).isEqualByComparingTo("3500");
    }

    @Test
    void aBadRowDoesNotStopTheGoodOnes() {
        when(productService.create(any(ProductDto.class))).thenAnswer(i -> {
            Product product = new Product();
            product.setId(UUID.randomUUID());
            return product;
        });
        when(itemRepo.save(any(PriceListItem.class))).thenAnswer(i -> i.getArgument(0));
        when(serviceRepo.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(serviceRepo.existsByCode(anyString())).thenReturn(false);
        when(priceListService.createService(any(ProcessingServiceDto.class))).thenAnswer(i -> {
            ProcessingService service = new ProcessingService();
            service.setId(UUID.randomUUID());
            return service;
        });
        when(servicePriceRepo.save(any(ServicePrice.class))).thenAnswer(i -> i.getArgument(0));

        ProductImportService.Result result = importer.importFile(workbook(
                List.of("GLASS", "", "CLEAR", "", "6", "", "", "2440", "1830", "28,000 - 30,000", "", "", "", ""),
                List.of("ACCESSORY", "", "", "", "", "Masking tape", "PIECE", "", "", "", "390", "", "", "")));

        assertThat(result.glassCreated()).isZero();
        assertThat(result.accessoriesCreated()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).isEqualTo("product.import.onePrice");
    }

    @Test
    void theTemplateHasTheColumnsTheImporterReads() {
        byte[] bytes = importer.template();

        assertThat(bytes).isNotEmpty();
        List<List<String>> rows = Excel.read(new java.io.ByteArrayInputStream(bytes));
        assertThat(rows).anySatisfy(row -> assertThat(row).contains("Type", "Sheet price (RWF)"));
    }

    private static MockMultipartFile workbook(List<String>... rows) {
        List<Excel.Row> data = java.util.Arrays.stream(rows)
                .map(row -> Excel.Row.of(row.toArray())).toList();
        Excel.Sheet sheet = new Excel.Sheet("Products", "Products", "Import", HEADERS, data);
        byte[] bytes = Excel.write(sheet);
        return new MockMultipartFile("file", "products.xlsx", Excel.CONTENT_TYPE, bytes);
    }
}
