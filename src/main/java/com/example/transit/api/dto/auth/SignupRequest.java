package com.example.transit.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param password 평문. BCrypt가 72바이트까지만 반영하므로 상한을 둔다.
 */
public record SignupRequest(
        @Email @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72) String password
) {
}
