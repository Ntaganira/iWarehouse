package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : EbmDevice.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The EBM device as the VSDC initialised it (/initializer/selectInitInfo) for a TIN, branch and device
 *               serial: the taxpayer and branch names, device, SDC ID and MRC, and the last numbers RRA had (our
 *               EBM invoice numbers go on from them). A device installed before (VSDC answer 902) is recorded without
 *               details. The day it was initialised counts the receipts' report numbers.
 * </pre>
 */
@Entity
@Table(name = "ebm_devices")
@AuditedEntity(ref = "deviceSerial")
@Getter
@Setter
@NoArgsConstructor
public class EbmDevice extends BaseEntity {

    @Column(nullable = false, length = 9)
    private String tin;

    @Column(name = "branch_id", nullable = false, length = 2)
    private String branchId;

    @Column(name = "device_serial", nullable = false, length = 100)
    private String deviceSerial;

    @Column(nullable = false)
    private boolean simulated;

    @Column(name = "already_installed", nullable = false)
    private boolean alreadyInstalled;

    @Column(name = "taxpayer_name", length = 60)
    private String taxpayerName;

    @Column(name = "branch_name", length = 60)
    private String branchName;

    @Column(name = "device_id", length = 20)
    private String deviceId;

    @Column(name = "sdc_id", length = 20)
    private String sdcId;

    @Column(name = "mrc_no", length = 20)
    private String mrcNo;

    @Column(name = "last_invc_no")
    private Long lastInvcNo;

    @Column(name = "last_sale_rcpt_no")
    private Long lastSaleRcptNo;

    @Column(name = "initialised_at", nullable = false)
    private LocalDateTime initialisedAt;
}
