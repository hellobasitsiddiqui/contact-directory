package com.example.contacts.controller;

import com.example.contacts.dto.ResetPasswordRequest;
import com.example.contacts.dto.UpdateEnabledRequest;
import com.example.contacts.dto.UpdateRoleRequest;
import com.example.contacts.dto.UserResponse;
import com.example.contacts.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrative user-management API. Every endpoint requires the {@code ADMIN}
 * role (enforced at the class level); a {@code USER} calling any of these
 * receives {@code 403 Forbidden}.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User management", description = "Admin-only management of user accounts")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "List all user accounts")
    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        return ResponseEntity.ok(userService.listUsers());
    }

    @Operation(summary = "Change a user's role")
    @PatchMapping("/{id}/role")
    public ResponseEntity<UserResponse> updateRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                userService.updateRole(id, request.role(), authentication.getName()));
    }

    @Operation(summary = "Enable or disable a user account")
    @PatchMapping("/{id}/enabled")
    public ResponseEntity<UserResponse> setEnabled(
            @PathVariable Long id,
            @Valid @RequestBody UpdateEnabledRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                userService.setEnabled(id, request.enabled(), authentication.getName()));
    }

    @Operation(summary = "Reset a user's password")
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<UserResponse> resetPassword(
            @PathVariable Long id,
            @Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(userService.resetPassword(id, request.password()));
    }

    @Operation(summary = "Delete a user account")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            Authentication authentication) {
        userService.deleteUser(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
