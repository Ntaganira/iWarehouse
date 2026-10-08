package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.Email;
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
 * - File      : UserDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Add/edit user form. Username is fixed after creation; the password is set here only
 *               when creating (later through Reset password); status has its own Disable/Enable action.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class UserDto {

    private Long id;

    @NotBlank(message = "{user.username.required}")
    @Pattern(regexp = "^[A-Za-z0-9._-]{3,50}$", message = "{user.username.pattern}")
    private String username;

    @NotBlank(message = "{user.fullName.required}")
    @Size(max = 100, message = "{user.fullName.size}")
    private String fullName;

    @NotBlank(message = "{user.email.required}")
    @Email(message = "{user.email.invalid}")
    @Size(max = 100, message = "{user.email.size}")
    private String email;

    @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "{user.phone.pattern}")
    private String phone;

    /** Required when creating; ignored when editing. */
    private String password;

    private Set<Long> roleIds = new HashSet<>();
}
