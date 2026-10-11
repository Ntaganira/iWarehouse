package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : ProfileDto.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : My profile: what a user keeps up to date themselves. The username, roles and status stay with the
 *               administrators (UserDto); the rules on name, email and phone are the same as theirs.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class ProfileDto {

    @NotBlank(message = "{user.fullName.required}")
    @Size(max = 100, message = "{user.fullName.size}")
    private String fullName;

    @NotBlank(message = "{user.email.required}")
    @Email(message = "{user.email.invalid}")
    @Size(max = 100, message = "{user.email.size}")
    private String email;

    @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "{user.phone.pattern}")
    private String phone;

    @Size(max = 100, message = "{user.jobTitle.size}")
    private String jobTitle;

    public static ProfileDto of(User user) {
        ProfileDto dto = new ProfileDto();
        dto.setFullName(user.getFullName());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setJobTitle(user.getJobTitle());
        return dto;
    }
}
