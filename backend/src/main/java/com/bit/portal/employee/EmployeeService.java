package com.bit.portal.employee;

import com.bit.portal.auth.AccountMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("portal-dev")
public class EmployeeService {
    private final EmployeeMapper employees;
    private final AccountMapper accounts;
    private final PasswordEncoder encoder;
    public EmployeeService(EmployeeMapper employees, AccountMapper accounts, PasswordEncoder encoder) {
        this.employees = employees; this.accounts = accounts; this.encoder = encoder;
    }
    public List<Employee> list() { return employees.findAll(); }
    public Employee get(String id) {
        var employee = employees.findById(id);
        if (employee == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "직원을 찾을 수 없습니다.");
        return employee;
    }

    @Transactional
    public Employee create(CreateEmployeeRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        var existing = employees.findForUpdate(request.employeeId());
        if (existing == null) {
            employees.insert(request.employeeId(), request.fullName().trim(), request.dateOfBirth());
        } else if (!"ACTIVE".equals(existing.employmentStatus())
                || !existing.fullName().equals(request.fullName().trim())
                || !Objects.equals(existing.dateOfBirth(), request.dateOfBirth())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "기존 직원 정보와 일치하지 않습니다. 사번과 인적사항을 확인하세요.");
        }
        // Fixed USER role: a request body cannot grant administrator privileges.
        accounts.insert(request.employeeId(), request.username(), encoder.encode(request.password()), "USER");
        return get(request.employeeId());
    }

    @Transactional
    public Employee updateContact(String id, ContactUpdateRequest request) {
        var employee = employees.findForUpdate(id);
        if (employee == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "직원을 찾을 수 없습니다.");
        // The same row lock is used by termination: a completed retirement cannot race with a save.
        if (!"ACTIVE".equals(employee.employmentStatus())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "퇴사한 직원은 정보를 수정할 수 없습니다.");
        }
        employees.updateContact(id, request.email(), request.phone());
        return get(id);
    }

    @Transactional
    public void terminate(String id) {
        if (employees.findForUpdate(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "직원을 찾을 수 없습니다.");
        }
        employees.terminate(id);
        accounts.disableEmployee(id);
    }
}
