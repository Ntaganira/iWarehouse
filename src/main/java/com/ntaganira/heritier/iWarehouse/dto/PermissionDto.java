package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : PermissionDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Edit permission form: only the label and description. Code, module and action are
 *               referenced by @PreAuthorize and seeded by migrations, so they are read-only.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PermissionDto {

    private Long id;

    @NotBlank(message = "{perm.name.required}")
    @Size(max = 150, message = "{perm.name.size}")
    private String name;

    @Size(max = 500, message = "{perm.description.size}")
    private String description;
}
