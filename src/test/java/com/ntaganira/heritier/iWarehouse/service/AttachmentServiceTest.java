package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Attachment;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Documents on records (SRS 3.1): a PDF or a photo told by its first bytes, at most 5 MB, a clean name; stored before its
 * row is saved; removed with who, when and why, the file kept.
 */
class AttachmentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final byte[] PDF = "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P'};

    private final AttachmentRepository repo = mock(AttachmentRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
    private final AttachmentService service = new AttachmentService(repo, storage, mock(GoodsReceiptRepository.class), shipmentRepo,
            mock(SupplierInvoiceRepository.class), mock(StockAdjustmentRepository.class), CLOCK);
    private final UUID shipment = UUID.randomUUID();

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theTypeIsToldByTheFirstBytesNeverByTheName() {
        assertThat(Documents.detect(PDF)).isEqualTo("application/pdf");
        assertThat(Documents.detect(PNG)).isEqualTo("image/png");
        assertThat(Documents.detect(JPEG)).isEqualTo("image/jpeg");
        assertThat(Documents.detect(WEBP)).isEqualTo("image/webp");
        assertThat(Documents.detect("<html><script>".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(Documents.detect(new byte[]{1, 2})).isNull();
        assertThat(Documents.extension("image/jpeg")).isEqualTo(".jpg");
        assertThat(Documents.cleanName("C:\\Users\\me\\Desktop\\customs \"entry\".pdf")).isEqualTo("customs entry.pdf");
        assertThat(Documents.cleanName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(Documents.cleanName("  ")).isEqualTo("document");
        assertThat(Documents.cleanName("a".repeat(250) + ".pdf")).hasSize(200).endsWith(".pdf");
    }

    @Test
    void aDocumentIsStoredUnderItsRecordThenRecorded() {
        when(shipmentRepo.existsById(shipment)).thenReturn(true);
        when(repo.save(any(Attachment.class))).thenAnswer(i -> i.getArgument(0));

        Attachment a = service.add(AttachmentOwner.SHIPMENT, shipment, "C:\\scans\\customs.pdf", PDF, "  Entry 2026/118  ");

        assertThat(a.getObjectKey()).startsWith("shipment/" + shipment + "/").endsWith(".pdf");
        assertThat(a.getFileName()).isEqualTo("customs.pdf");
        assertThat(a.getContentType()).isEqualTo("application/pdf");
        assertThat(a.getSizeBytes()).isEqualTo(PDF.length);
        assertThat(a.getNote()).isEqualTo("Entry 2026/118");
        assertThat(a.getSizeLabel()).isEqualTo("1 KB");
        verify(storage).put(a.getObjectKey(), PDF, "application/pdf");
    }

    @Test
    void refusedFilesAreNeverStored() {
        when(shipmentRepo.existsById(shipment)).thenReturn(true);
        assertThatThrownBy(() -> service.add(AttachmentOwner.SHIPMENT, shipment, "page.html", "<html>".getBytes(StandardCharsets.US_ASCII), null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("attachment.file.type");
        assertThatThrownBy(() -> service.add(AttachmentOwner.SHIPMENT, shipment, "empty.pdf", new byte[0], null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("attachment.file.required");
        byte[] big = new byte[(int) Documents.MAX_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertThatThrownBy(() -> service.add(AttachmentOwner.SHIPMENT, shipment, "big.pdf", big, null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("attachment.file.tooBig");
        assertThatThrownBy(() -> service.add(AttachmentOwner.SHIPMENT, UUID.randomUUID(), "x.pdf", PDF, null))
                .isInstanceOf(NotFoundException.class);
        verify(storage, never()).put(anyString(), any(), anyString());
        verify(repo, never()).save(any());
    }

    @Test
    void removingMarksItWithWhoWhenAndWhyOnce() {
        AppUserPrincipal principal = new AppUserPrincipal(7L, "Supervisor", "supervisor1", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        Attachment a = new Attachment();
        a.setFileName("photo.jpg");
        a.setContentType("image/jpeg");
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(a));

        service.remove(id, "  Wrong shipment  ");

        assertThat(a.isRemoved()).isTrue();
        assertThat(a.getRemovedBy()).isEqualTo("supervisor1");
        assertThat(a.getRemoveReason()).isEqualTo("Wrong shipment");
        assertThat(a.isImage()).isTrue();
        assertThatThrownBy(() -> service.remove(id, "again")).isInstanceOf(BusinessException.class);
    }
}
