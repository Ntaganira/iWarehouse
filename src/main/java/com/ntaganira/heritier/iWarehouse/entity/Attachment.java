package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Attachment.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A document kept on a record (a PDF or a photo), stored in MinIO under object_key: its name, type, size
 *               and a note. Removed by marking it (who, when, why), never deleted: the file stays in storage and the
 *               History shows it.
 * </pre>
 */
@Entity
@Table(name = "attachments")
@AuditedEntity(ref = "fileName")
@Getter
@Setter
@NoArgsConstructor
public class Attachment extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private AttachmentOwner ownerType;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "object_key", nullable = false, length = 200)
    private String objectKey;

    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 60)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 255)
    private String note;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    @Column(name = "removed_by", length = 50)
    private String removedBy;

    @Column(name = "remove_reason", length = 255)
    private String removeReason;

    public boolean isImage() {
        return contentType.startsWith("image/");
    }

    public boolean isRemoved() {
        return removedAt != null;
    }

    /** "245 KB" or "1.2 MB". */
    public String getSizeLabel() {
        if (sizeBytes < 1024 * 1024) {
            return Math.max(1, Math.round(sizeBytes / 1024.0)) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
    }
}
