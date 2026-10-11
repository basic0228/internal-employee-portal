package com.bit.portal.employee;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record Employee(String employeeId, String fullName, LocalDate dateOfBirth,
        String employmentStatus, OffsetDateTime updatedAt, String email, String phone) { }
