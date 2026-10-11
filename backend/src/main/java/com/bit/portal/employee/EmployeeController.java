package com.bit.portal.employee;

import com.bit.portal.auth.PortalPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("portal-dev")
public class EmployeeController {
    private final EmployeeService service;
    public EmployeeController(EmployeeService service) { this.service = service; }

    @GetMapping("/api/admin/employees")
    public List<Employee> list() { return service.list(); }

    @GetMapping("/api/admin/employees/{id}")
    public Employee detail(@PathVariable String id) { return service.get(id); }

    @PostMapping("/api/admin/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public Employee create(@Valid @RequestBody CreateEmployeeRequest request) { return service.create(request); }

    @PostMapping("/api/admin/employees/{id}/termination")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void terminate(@PathVariable String id) { service.terminate(id); }

    @GetMapping("/api/me")
    public Employee me(@AuthenticationPrincipal PortalPrincipal principal) {
        if (principal.employeeId() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 직원 정보가 없습니다.");
        // Ownership comes from the authenticated principal, never from a client supplied employeeId.
        return service.get(principal.employeeId());
    }

    @PutMapping("/api/me/contact")
    public Employee updateContact(@AuthenticationPrincipal PortalPrincipal principal,
            @Valid @RequestBody ContactUpdateRequest request) {
        if (principal.employeeId() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 직원 정보가 없습니다.");
        return service.updateContact(principal.employeeId(), request);
    }
}
