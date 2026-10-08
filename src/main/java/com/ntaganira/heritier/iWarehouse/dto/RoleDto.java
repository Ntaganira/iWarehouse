package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : RoleDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Add/edit role form. The Spring authority is ROLE_ + code; the code is fixed after
 *               creation because migrations grant pages and permissions by role code.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class RoleDto {

    private Long id;

    @NotBlank(message = "{role.code.required}")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,44}$", message = "{role.code.pattern}")
    private String code;

    @NotBlank(message = "{role.description.required}")
    @Size(max = 255, message = "{role.description.size}")
    private String description;

    private Set<Long> permissionIds = new HashSet<>();

    private Set<Long> pageIds = new HashSet<>();
}
