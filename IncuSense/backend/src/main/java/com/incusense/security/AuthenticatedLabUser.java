package com.incusense.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Principal that carries the tenant (lab) identity, so controllers can scope
 * data access per-lab via {@code @AuthenticationPrincipal AuthenticatedLabUser}.
 */
public class AuthenticatedLabUser implements UserDetails {

    private final String username;
    private final String labId;
    private final String role;

    public AuthenticatedLabUser(String username, String labId, String role) {
        this.username = username;
        this.labId = labId;
        this.role = role;
    }

    public String getLabId() {
        return labId;
    }

    public String getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        String r = role == null ? "LAB_USER" : role;
        return List.of(new SimpleGrantedAuthority("ROLE_" + r));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
