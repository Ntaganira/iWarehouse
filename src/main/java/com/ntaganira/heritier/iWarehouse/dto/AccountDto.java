package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : AccountDto.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Add/edit account form (ACC-03). The system key is not on the form: it comes with the
 *               accounts V16 seeds and never changes.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class AccountDto {

    private UUID id;

    @NotBlank(message = "{account.code.required}")
    @Pattern(regexp = "^[0-9][0-9A-Z.-]{1,9}$", message = "{account.code.pattern}")
    private String code;

    @NotBlank(message = "{account.name.required}")
    @Size(max = 80, message = "{account.name.size}")
    private String name;

    @NotNull(message = "{account.type.required}")
    private AccountType type;

    @Size(max = 255, message = "{account.description.size}")
    private String description;

    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
