package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Attachment;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AttachmentService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Documents kept on records (SRS 3.1): a PDF or a photo (Documents) is stored in MinIO under
 *               "&lt;owner&gt;/&lt;record id&gt;/&lt;uuid&gt;.&lt;ext&gt;", then its row is saved (audited). Removing one marks it with who,
 *               when and why (the caller gives the reason to AuditContext); the file stays. Who may see a document is
 *               checked by the controller with its owner's page authority.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class AttachmentService {

    private final AttachmentRepository repo;
    private final FileStorageService storage;
    private final GoodsReceiptRepository receiptRepo;
    private final ShipmentRepository shipmentRepo;
    private final SupplierInvoiceRepository supplierInvoiceRepo;
    private final StockAdjustmentRepository adjustmentRepo;
    private final VehicleRepository vehicleRepo;
    private final DriverRepository driverRepo;
    private final TripRepository tripRepo;
    private final VehicleCostRepository vehicleCostRepo;
    private final CreditNoteRequestRepository creditNoteRequestRepo;
    private final ReceivableWriteOffRepository writeOffRepo;
    private final CustomerRepository customerRepo;
    private final SupplierRepository supplierRepo;
    private final CreditApplicationRepository creditApplicationRepo;
    private final SiteTransferRepository siteTransferRepo;
    private final CuttingJobRepository cuttingJobRepo;
    private final DeliveryNoteRepository deliveryNoteRepo;
    private final CashDepositRepository cashDepositRepo;
    private final BalanceConfirmationRepository confirmationRepo;
    private final DailyCloseRepository dailyCloseRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.DamageReportRepository damageRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.StockCountRepository countRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.StockVarianceRepository varianceRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.ExpenseBillRepository expenseBillRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.PurchaseRequisitionRepository requisitionRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.PettyCashVoucherRepository voucherRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.StaffAdvanceRepository advanceRepo;
    private final Clock clock;

    public AttachmentService(AttachmentRepository repo, FileStorageService storage, GoodsReceiptRepository receiptRepo,
                             ShipmentRepository shipmentRepo, SupplierInvoiceRepository supplierInvoiceRepo,
                             StockAdjustmentRepository adjustmentRepo, VehicleRepository vehicleRepo, DriverRepository driverRepo,
                             TripRepository tripRepo, VehicleCostRepository vehicleCostRepo, Clock clock,
                             CreditNoteRequestRepository creditNoteRequestRepo, ReceivableWriteOffRepository writeOffRepo,
                             CustomerRepository customerRepo, SupplierRepository supplierRepo,
                             CreditApplicationRepository creditApplicationRepo, SiteTransferRepository siteTransferRepo,
                             CuttingJobRepository cuttingJobRepo, DeliveryNoteRepository deliveryNoteRepo, CashDepositRepository cashDepositRepo,
                             BalanceConfirmationRepository confirmationRepo, DailyCloseRepository dailyCloseRepo,
                             com.ntaganira.heritier.iWarehouse.repository.DamageReportRepository damageRepo,
                             com.ntaganira.heritier.iWarehouse.repository.StockCountRepository countRepo,
                             com.ntaganira.heritier.iWarehouse.repository.StockVarianceRepository varianceRepo,
                             com.ntaganira.heritier.iWarehouse.repository.ExpenseBillRepository expenseBillRepo,
                             com.ntaganira.heritier.iWarehouse.repository.PurchaseRequisitionRepository requisitionRepo,
                             com.ntaganira.heritier.iWarehouse.repository.PettyCashVoucherRepository voucherRepo,
                             com.ntaganira.heritier.iWarehouse.repository.StaffAdvanceRepository advanceRepo) {
        this.expenseBillRepo = expenseBillRepo;
        this.requisitionRepo = requisitionRepo;
        this.voucherRepo = voucherRepo;
        this.advanceRepo = advanceRepo;
        this.damageRepo = damageRepo;
        this.countRepo = countRepo;
        this.varianceRepo = varianceRepo;
        this.cashDepositRepo = cashDepositRepo;
        this.confirmationRepo = confirmationRepo;
        this.dailyCloseRepo = dailyCloseRepo;
        this.cuttingJobRepo = cuttingJobRepo;
        this.deliveryNoteRepo = deliveryNoteRepo;
        this.siteTransferRepo = siteTransferRepo;
        this.customerRepo = customerRepo;
        this.supplierRepo = supplierRepo;
        this.creditApplicationRepo = creditApplicationRepo;
        this.creditNoteRequestRepo = creditNoteRequestRepo;
        this.writeOffRepo = writeOffRepo;
        this.repo = repo;
        this.storage = storage;
        this.receiptRepo = receiptRepo;
        this.shipmentRepo = shipmentRepo;
        this.supplierInvoiceRepo = supplierInvoiceRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.vehicleRepo = vehicleRepo;
        this.driverRepo = driverRepo;
        this.tripRepo = tripRepo;
        this.vehicleCostRepo = vehicleCostRepo;
        this.clock = clock;
    }

    /** A record's documents, newest first, a page at a time. */
    public Page<Attachment> page(AttachmentOwner owner, UUID ownerId, int page, int size) {
        return repo.findByOwnerTypeAndOwnerIdAndRemovedAtIsNullOrderByCreatedAtDesc(owner, ownerId, PageRequest.of(page, size));
    }

    public long count(AttachmentOwner owner, UUID ownerId) {
        return repo.countByOwnerTypeAndOwnerIdAndRemovedAtIsNull(owner, ownerId);
    }

    public Attachment find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Attachment", id));
    }

    /** Keeps a document on a record: stored, then recorded. */
    @Transactional
    public Attachment add(AttachmentOwner owner, UUID ownerId, String originalName, byte[] bytes, String note) {
        requireOwner(owner, ownerId);
        if (bytes == null || bytes.length == 0) {
            throw BusinessException.onField("file", "attachment.file.required");
        }
        if (bytes.length > Documents.MAX_BYTES) {
            throw BusinessException.onField("file", "attachment.file.tooBig");
        }
        String type = Documents.detect(bytes);
        if (type == null) {
            throw BusinessException.onField("file", "attachment.file.type");
        }
        String key = owner.name().toLowerCase().replace('_', '-') + "/" + ownerId + "/" + UUID.randomUUID() + Documents.extension(type);
        storage.put(key, bytes, type);
        Attachment a = new Attachment();
        a.setOwnerType(owner);
        a.setOwnerId(ownerId);
        a.setObjectKey(key);
        a.setFileName(Documents.cleanName(originalName));
        a.setContentType(type);
        a.setSizeBytes(bytes.length);
        a.setNote(PartyRules.clean(note));
        return repo.save(a);
    }

    /** Takes a document off its record (the file stays), with the reason. */
    @Transactional
    public Attachment remove(UUID id, String reason) {
        Attachment a = find(id);
        if (a.isRemoved()) {
            throw BusinessException.of("attachment.removed", a.getFileName());
        }
        a.setRemovedAt(LocalDateTime.now(clock));
        a.setRemovedBy(AppUserPrincipal.currentUsername());
        a.setRemoveReason(reason.trim());
        return a;
    }

    /** The document's content; the caller closes it. */
    public InputStream open(Attachment a) {
        return storage.open(a.getObjectKey());
    }

    private void requireOwner(AttachmentOwner owner, UUID id) {
        boolean exists = switch (owner) {
            case GOODS_RECEIPT -> receiptRepo.existsById(id);
            case SHIPMENT -> shipmentRepo.existsById(id);
            case SUPPLIER_INVOICE -> supplierInvoiceRepo.existsById(id);
            case STOCK_ADJUSTMENT -> adjustmentRepo.existsById(id);
            case VEHICLE -> vehicleRepo.existsById(id);
            case DRIVER -> driverRepo.existsById(id);
            case TRIP -> tripRepo.existsById(id);
            case VEHICLE_COST -> vehicleCostRepo.existsById(id);
            case CREDIT_NOTE_REQUEST -> creditNoteRequestRepo.existsById(id);
            case RECEIVABLE_WRITE_OFF -> writeOffRepo.existsById(id);
            case CUSTOMER -> customerRepo.existsById(id);
            case SUPPLIER -> supplierRepo.existsById(id);
            case CREDIT_APPLICATION -> creditApplicationRepo.existsById(id);
            case SITE_TRANSFER -> siteTransferRepo.existsById(id);
            case CUTTING_JOB -> cuttingJobRepo.existsById(id);
            case DELIVERY_NOTE -> deliveryNoteRepo.existsById(id);
            case CASH_DEPOSIT -> cashDepositRepo.existsById(id);
            case BALANCE_CONFIRMATION -> confirmationRepo.existsById(id);
            case DAILY_CLOSE -> dailyCloseRepo.existsById(id);
            case DAMAGE_REPORT -> damageRepo.existsById(id);
            case STOCK_COUNT -> countRepo.existsById(id);
            case STOCK_VARIANCE -> varianceRepo.existsById(id);
            case EXPENSE_BILL -> expenseBillRepo.existsById(id);
            case PURCHASE_REQUISITION -> requisitionRepo.existsById(id);
            case PETTY_CASH_VOUCHER -> voucherRepo.existsById(id);
            case STAFF_ADVANCE -> advanceRepo.existsById(id);
        };
        if (!exists) {
            throw new NotFoundException(owner.name(), id);
        }
    }
}
