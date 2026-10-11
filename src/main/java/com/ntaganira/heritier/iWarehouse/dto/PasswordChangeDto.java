package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : PasswordChangeDto.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Changing one's own password: the current one, then the new one twice. The rules (length, different,
 *               confirmed) are ProfileService's, so each refusal is shown under its own field.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PasswordChangeDto {

    @NotBlank(message = "{profile.password.currentRequired}")
    private String currentPassword;

    @NotBlank(message = "{profile.password.newRequired}")
    private String newPassword;

    @NotBlank(message = "{profile.password.confirmRequired}")
    private String confirmPassword;
}
