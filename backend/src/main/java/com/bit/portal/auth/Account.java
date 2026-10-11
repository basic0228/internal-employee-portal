package com.bit.portal.auth;

// Internal DB model only: never return the password hash through an API.
public record Account(long id, String employeeId, String username, String passwordHash,
        String role, boolean enabled, String employmentStatus) {
    public boolean canAccess() {
        return enabled && (employeeId == null ? "ADMIN".equals(role) : "ACTIVE".equals(employmentStatus));
    }
    @Override
    public String toString() { return "Account[id=" + id + ", role=" + role + "]"; }
}
