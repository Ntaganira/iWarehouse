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
import com.ntaganira.heritier.iWarehouse.enums.GlassType;
import com.ntaganira.heritier.iWarehouse.enums.ProcessingServiceKind;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.PriceListItemRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProcessingServiceRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.ServicePriceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ProductImportService.java
 * - Date      : 2026. 10. 27.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Excel import of glass products and accessories (MD-01, MD-06). Each glass row carries the
 *               sheet it is sold as; the largest sheet of a product sets its default RETAIL price per m2,
 *               VAT included. Accessories become catalogue-only processing services priced per unit.
 *               Nothing here creates stock units or changes an existing price.
 * </pre>
 */
@Service
public class ProductImportService {

    private static final List<String> HEADERS = List.of(
            "Type", "Code", "Glass type", "Colour / finish / pattern", "Thickness (mm)",
            "Accessory name", "Accessory unit", "Sheet width (mm)", "Sheet height (mm)",
            "Sheet price (RWF)", "Unit price (RWF)", "Reorder level (m2)", "Max level (m2)", "Notes");

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final Set<String> ACCESSORY_UNITS = Set.of(
            "M2", "METRE", "PIECE", "ROLL", "TUBE", "KG", "LITRE", "SET", "BOX", "PACK", "BAG");

    private final ProductService productService;
    private final PriceListService priceListService;
    private final ProductRepository productRepo;
    private final ProcessingServiceRepository serviceRepo;
    private final PriceListItemRepository itemRepo;
    private final ServicePriceRepository servicePriceRepo;
    private final Messages messages;
    private final TransactionTemplate transaction;

    public ProductImportService(ProductService productService, PriceListService priceListService,
                                ProductRepository productRepo, ProcessingServiceRepository serviceRepo,
                                PriceListItemRepository itemRepo, ServicePriceRepository servicePriceRepo,
                                Messages messages, PlatformTransactionManager transactionManager) {
        this.productService = productService;
        this.priceListService = priceListService;
        this.productRepo = productRepo;
        this.serviceRepo = serviceRepo;
        this.itemRepo = itemRepo;
        this.servicePriceRepo = servicePriceRepo;
        this.messages = messages;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** A line the importer refused, by its Excel row number (the header is row 1 when it is the first row). */
    public record RowError(int row, String message) {
    }

    /** What the import did; a duplicate or an existing record is skipped, never changed. */
    public record Result(int glassCreated, int glassSkipped, int accessoriesCreated, int accessoriesSkipped,
                         int mergedGlassRows, List<RowError> errors) {

        public int created() {
            return glassCreated + accessoriesCreated;
        }

        public int skipped() {
            return glassSkipped + accessoriesSkipped;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /** The clean workbook: the columns, then instructions in the user's language. */
    public byte[] template() {
        List<Excel.Row> instructions = List.of(
                Excel.Row.of("1", messages.get("product.import.template.step1")),
                Excel.Row.of("2", messages.get("product.import.template.step2")),
                Excel.Row.of("3", messages.get("product.import.template.step3")),
                Excel.Row.of("4", messages.get("product.import.template.step4")),
                Excel.Row.of("5", messages.get("product.import.template.step5")),
                Excel.Row.of("6", messages.get("product.import.template.step6")));
        Excel.Sheet products = new Excel.Sheet("Products", messages.get("product.import.template.title"),
                messages.get("product.import.template.subtitle"), HEADERS, List.of());
        Excel.Sheet help = new Excel.Sheet("Instructions", messages.get("product.import.template.helpTitle"),
                messages.get("product.import.template.helpSubtitle"), List.of("Step", "What to do"), instructions);
        return Excel.write(products, help);
    }

    /** Reads the first sheet; valid rows import even when another row is refused. */
    public Result importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of("product.import.file.required");
        }
        List<List<String>> rows;
        try (InputStream in = file.getInputStream()) {
            rows = Excel.read(in);
        } catch (IOException | RuntimeException e) {
            throw BusinessException.of("product.import.read");
        }

        int headerAt = -1;
        Map<String, Integer> columns = Map.of();
        for (int r = 0; r < Math.min(rows.size(), 20); r++) {
            Map<String, Integer> found = columns(rows.get(r));
            if (found.containsKey("type")) {
                headerAt = r;
                columns = found;
                break;
            }
        }
        if (headerAt < 0) {
            throw BusinessException.of("product.import.header");
        }

        PriceList list = priceListService.defaultList();
        UUID taxCategoryId = productService.defaultTaxCategoryId();
        if (taxCategoryId == null) {
            throw BusinessException.of("product.import.taxMissing");
        }
        Map<String, GlassDraft> glasses = new LinkedHashMap<>();
        List<AccessoryDraft> accessories = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int merged = 0;

        for (int r = headerAt + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            int excelRow = r + 1;
            try {
                String type = text(row, columns, "type").toUpperCase(Locale.ROOT);
                if ("GLASS".equals(type)) {
                    GlassDraft draft = glass(row, columns, excelRow, taxCategoryId);
                    GlassDraft kept = glasses.get(draft.key());
                    if (kept == null) {
                        glasses.put(draft.key(), draft);
                    } else {
                        merged++;
                        if (draft.area().compareTo(kept.area()) > 0) {
                            glasses.put(draft.key(), draft);
                        }
                    }
                } else if ("ACCESSORY".equals(type)) {
                    accessories.add(accessory(row, columns, excelRow));
                } else {
                    throw new RowProblem("product.import.type", type);
                }
            } catch (RowProblem e) {
                errors.add(new RowError(excelRow, messages.get(e.key, e.args)));
            }
        }

        int glassCreated = 0;
        int glassSkipped = 0;
        for (GlassDraft draft : glasses.values()) {
            if (productRepo.existsIdentity(draft.dto().getGlassType(),
                    draft.dto().getVariant() == null ? "" : draft.dto().getVariant(),
                    draft.dto().getThicknessMm())) {
                glassSkipped++;
                continue;
            }
            try {
                transaction.executeWithoutResult(status -> {
                    Product product = productService.create(draft.dto());
                    if (itemRepo.findByPriceList_IdAndProduct_Id(list.getId(), product.getId()).isEmpty()) {
                        PriceListItem item = new PriceListItem();
                        item.setPriceList(list);
                        item.setProduct(product);
                        item.setPricePerM2(draft.pricePerM2());
                        itemRepo.save(item);
                    }
                });
                glassCreated++;
            } catch (BusinessException e) {
                errors.add(new RowError(draft.row(), messages.get(e.getMessageKey(), e.getArgs())));
            } catch (RuntimeException e) {
                errors.add(new RowError(draft.row(), messages.get("product.import.rowFailed")));
            }
        }

        int accessoriesCreated = 0;
        int accessoriesSkipped = 0;
        Set<String> names = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        for (AccessoryDraft draft : accessories) {
            String nameKey = draft.name().toLowerCase(Locale.ROOT);
            if (!names.add(nameKey)) {
                errors.add(new RowError(draft.row(), messages.get("product.import.duplicate", draft.name())));
                accessoriesSkipped++;
                continue;
            }
            if (serviceRepo.existsByNameIgnoreCase(draft.name())) {
                accessoriesSkipped++;
                continue;
            }
            String code = draft.code().isBlank() ? freeAccessoryCode(draft.name()) : draft.code();
            if (!codes.add(code) || serviceRepo.existsByCode(code)) {
                errors.add(new RowError(draft.row(), messages.get("product.import.codeTaken", code)));
                accessoriesSkipped++;
                continue;
            }
            try {
                transaction.executeWithoutResult(status -> {
                    ProcessingService service = priceListService.createService(draft.dto(code));
                    if (servicePriceRepo.findByPriceList_IdAndService_Id(list.getId(), service.getId()).isEmpty()) {
                        ServicePrice price = new ServicePrice();
                        price.setPriceList(list);
                        price.setService(service);
                        price.setPrice(draft.price());
                        servicePriceRepo.save(price);
                    }
                });
                accessoriesCreated++;
            } catch (BusinessException e) {
                errors.add(new RowError(draft.row(), messages.get(e.getMessageKey(), e.getArgs())));
            } catch (RuntimeException e) {
                errors.add(new RowError(draft.row(), messages.get("product.import.rowFailed")));
            }
        }

        return new Result(glassCreated, glassSkipped, accessoriesCreated, accessoriesSkipped, merged,
                List.copyOf(errors));
    }

    private static Map<String, Integer> columns(List<String> row) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int c = 0; c < row.size(); c++) {
            String key = switch (normalize(row.get(c))) {
                case "TYPE", "PRODUCTTYPE" -> "type";
                case "CODE", "PRODUCTCODE", "ACCESSORYCODE" -> "code";
                case "GLASSTYPE", "GLASS", "TYPEGLASS" -> "glasstype";
                case "COLOURFINISHPATTERN", "COLOUR", "COLOR", "VARIANT", "FINISH", "PATTERN" -> "variant";
                case "THICKNESSMM", "THICKNESS" -> "thickness";
                case "ACCESSORYNAME", "NAME", "ITEMNAME" -> "name";
                case "ACCESSORYUNIT", "UNIT", "CHARGEUNIT" -> "unit";
                case "SHEETWIDTHMM", "WIDTHMM", "WIDTH" -> "width";
                case "SHEETHEIGHTMM", "HEIGHTMM", "HEIGHT" -> "height";
                case "SHEETPRICERWF", "SHEETPRICE" -> "sheetprice";
                case "UNITPRICERWF", "UNITPRICE", "ACCESSORYPRICE" -> "unitprice";
                case "REORDERLEVELM2", "REORDERLEVEL", "REORDER" -> "reorder";
                case "MAXLEVELM2", "MAXLEVEL", "MAXIMUMLEVEL" -> "maxlevel";
                case "NOTES", "NOTE" -> "notes";
                default -> null;
            };
            if (key != null) {
                found.putIfAbsent(key, c);
            }
        }
        return found;
    }

    private static GlassDraft glass(List<String> row, Map<String, Integer> columns, int excelRow,
                                    UUID taxCategoryId) {
        String typeText = required(row, columns, "glasstype", "product.import.glassType");
        GlassType type = glassType(typeText);
        String variant = GlassProducts.normalizeVariant(text(row, columns, "variant"));
        BigDecimal thickness = decimal(row, columns, "thickness", "product.import.thickness");
        if (thickness.compareTo(BigDecimal.ONE) < 0 || thickness.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw new RowProblem("product.import.thickness", typeText);
        }
        int width = integer(row, columns, "width", "product.import.size");
        int height = integer(row, columns, "height", "product.import.size");
        if (width < 1 || height < 1 || width > 10_000 || height > 10_000) {
            throw new RowProblem("product.import.size", width + " x " + height);
        }
        BigDecimal sheetPrice = decimal(row, columns, "sheetprice", "product.import.priceRequired");
        if (sheetPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RowProblem("product.import.price", sheetPrice.toPlainString());
        }
        BigDecimal area = BigDecimal.valueOf(width).multiply(BigDecimal.valueOf(height))
                .divide(ONE_MILLION, 4, RoundingMode.HALF_UP);
        BigDecimal pricePerM2 = sheetPrice.divide(area, 2, RoundingMode.HALF_UP);
        if (pricePerM2.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RowProblem("product.import.price", sheetPrice.toPlainString());
        }
        BigDecimal reorder = optionalDecimal(row, columns, "reorder", "product.import.reorder");
        BigDecimal max = optionalDecimal(row, columns, "maxlevel", "product.import.maxLevel");
        if (reorder != null && reorder.compareTo(BigDecimal.ZERO) < 0) {
            throw new RowProblem("product.import.reorder", reorder.toPlainString());
        }
        if (max != null && max.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RowProblem("product.import.maxLevel", max.toPlainString());
        }
        if (reorder != null && max != null && max.compareTo(reorder) < 0) {
            throw new RowProblem("product.import.maxBelowReorder");
        }
        String code = text(row, columns, "code").toUpperCase(Locale.ROOT);
        if (!code.isBlank() && !code.matches("^[A-Z0-9][A-Z0-9.-]{1,19}$")) {
            throw new RowProblem("product.import.code", code);
        }
        String notes = notes(row, columns);

        ProductDto dto = new ProductDto();
        dto.setCode(code.isBlank() ? null : code);
        dto.setGlassType(type);
        dto.setVariant(variant);
        dto.setTaxCategoryId(taxCategoryId);
        dto.setThicknessMm(thickness);
        dto.setReorderLevelM2(reorder);
        dto.setMaxLevelM2(max);
        dto.setNotes(notes);
        return new GlassDraft(excelRow, dto, area, pricePerM2);
    }

    private static AccessoryDraft accessory(List<String> row, Map<String, Integer> columns, int excelRow) {
        String name = required(row, columns, "name", "product.import.name").trim();
        if (name.length() > 60) {
            throw new RowProblem("product.import.nameSize", name);
        }
        String unitText = required(row, columns, "unit", "product.import.unit");
        ChargeUnit unit = chargeUnit(unitText);
        if (!ACCESSORY_UNITS.contains(unit.name())) {
            throw new RowProblem("product.import.unit", unitText);
        }
        BigDecimal price = decimal(row, columns, "unitprice", "product.import.priceRequired");
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RowProblem("product.import.price", price.toPlainString());
        }
        String code = text(row, columns, "code").toUpperCase(Locale.ROOT);
        if (!code.isBlank() && !code.matches("^[A-Z0-9][A-Z0-9_-]{1,19}$")) {
            throw new RowProblem("product.import.code", code);
        }
        return new AccessoryDraft(excelRow, name, unit, price, code);
    }

    private static String text(List<String> row, Map<String, Integer> columns, String key) {
        Integer c = columns.get(key);
        return c == null || c >= row.size() ? "" : row.get(c).trim();
    }

    private static String required(List<String> row, Map<String, Integer> columns, String key, String error) {
        String value = text(row, columns, key);
        if (value.isBlank()) {
            throw new RowProblem(error);
        }
        return value;
    }

    private static BigDecimal decimal(List<String> row, Map<String, Integer> columns, String key, String error) {
        String value = required(row, columns, key, error);
        return number(value, error);
    }

    private static BigDecimal optionalDecimal(List<String> row, Map<String, Integer> columns, String key,
                                               String error) {
        String value = text(row, columns, key);
        return value.isBlank() ? null : number(value, error);
    }

    private static BigDecimal number(String value, String error) {
        String clean = value.replace('\u00A0', ' ').replace(" ", "")
                .replaceAll("(?i)RWF", "").replaceAll("(?i)FRW", "");
        if (clean.matches(".*\\d[-\\u2013\\u2014]\\d.*")) {
            throw new RowProblem("product.import.onePrice", value);
        }
        int comma = clean.indexOf(',');
        if (comma >= 0 && clean.indexOf('.') < 0 && clean.indexOf(',', comma + 1) < 0
                && clean.length() - comma - 1 <= 2) {
            clean = clean.substring(0, comma) + "." + clean.substring(comma + 1);
        } else {
            clean = clean.replace(",", "");
        }
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            throw new RowProblem(error, value);
        }
    }

    private static int integer(List<String> row, Map<String, Integer> columns, String key, String error) {
        BigDecimal value = decimal(row, columns, key, error);
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw new RowProblem(error, value.toPlainString());
        }
    }

    private static String notes(List<String> row, Map<String, Integer> columns) {
        String notes = text(row, columns, "notes");
        if (notes.length() > 255) {
            throw new RowProblem("product.import.notesSize");
        }
        return notes.isBlank() ? null : notes;
    }

    private static GlassType glassType(String value) {
        String key = normalize(value);
        return switch (key) {
            case "CLEAR", "FLOAT", "CLEARFLOAT" -> GlassType.CLEAR;
            case "TINTED", "TINT" -> GlassType.TINTED;
            case "REFLECTIVE", "REF" -> GlassType.REFLECTIVE;
            case "FROSTED", "FROST" -> GlassType.FROSTED;
            case "TEMPERED", "TEMPER" -> GlassType.TEMPERED;
            case "LAMINATED", "LAM" -> GlassType.LAMINATED;
            case "MIRROR", "MIR" -> GlassType.MIRROR;
            default -> throw new RowProblem("product.import.glassType", value);
        };
    }

    private static ChargeUnit chargeUnit(String value) {
        String key = normalize(value);
        return switch (key) {
            case "M2", "SQUAREMETRE", "SQUAREMETER", "SQM" -> ChargeUnit.M2;
            case "METRE", "METER", "M", "LM" -> ChargeUnit.METRE;
            case "PIECE", "PIECES", "PC", "PCS", "UNIT" -> ChargeUnit.PIECE;
            case "HOLE", "HOLES" -> ChargeUnit.HOLE;
            case "ROLL", "ROLLS" -> ChargeUnit.ROLL;
            case "TUBE", "TUBES" -> ChargeUnit.TUBE;
            case "KG", "KILOGRAM", "KILOGRAMS" -> ChargeUnit.KG;
            case "LITRE", "LITER", "L" -> ChargeUnit.LITRE;
            case "SET", "SETS" -> ChargeUnit.SET;
            case "BOX", "BOXES" -> ChargeUnit.BOX;
            case "PACK", "PACKS", "PACKET" -> ChargeUnit.PACK;
            case "BAG", "BAGS" -> ChargeUnit.BAG;
            default -> throw new RowProblem("product.import.unit", value);
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private String freeAccessoryCode(String name) {
        String base = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.length() < 2) {
            base = "ACC";
        }
        base = base.substring(0, Math.min(base.length(), 20));
        String code = base;
        for (int n = 2; serviceRepo.existsByCode(code); n++) {
            String suffix = "-" + n;
            code = base.substring(0, Math.min(base.length(), 20 - suffix.length())) + suffix;
        }
        return code;
    }

    private record GlassDraft(int row, ProductDto dto, BigDecimal area, BigDecimal pricePerM2) {

        String key() {
            return dto.getGlassType() + "|" + (dto.getVariant() == null ? "" : dto.getVariant().toLowerCase(Locale.ROOT))
                    + "|" + dto.getThicknessMm().stripTrailingZeros().toPlainString();
        }
    }

    private record AccessoryDraft(int row, String name, ChargeUnit unit, BigDecimal price, String code) {

        ProcessingServiceDto dto(String code) {
            ProcessingServiceDto dto = new ProcessingServiceDto();
            dto.setCode(code);
            dto.setName(name);
            dto.setKind(ProcessingServiceKind.ACCESSORY);
            dto.setChargeUnit(unit);
            return dto;
        }
    }

    private static final class RowProblem extends RuntimeException {

        final String key;
        final Object[] args;

        RowProblem(String key, Object... args) {
            this.key = key;
            this.args = args;
        }
    }
}
