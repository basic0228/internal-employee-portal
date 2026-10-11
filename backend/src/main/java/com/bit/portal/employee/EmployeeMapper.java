package com.bit.portal.employee;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface EmployeeMapper {
    String COLUMNS = "employee_id, full_name, date_of_birth, employment_status, updated_at, email, phone";

    @Select("SELECT " + COLUMNS + " FROM employees ORDER BY employee_id")
    List<Employee> findAll();

    @Select("SELECT " + COLUMNS + " FROM employees WHERE employee_id = #{employeeId}")
    Employee findById(String employeeId);

    @Select("SELECT " + COLUMNS + " FROM employees WHERE employee_id = #{employeeId} FOR UPDATE")
    Employee findForUpdate(String employeeId);

    @Insert("INSERT INTO employees (employee_id, full_name, date_of_birth) VALUES (#{id}, #{name}, #{birth})")
    int insert(@Param("id") String id, @Param("name") String name, @Param("birth") LocalDate birth);

    @Update("""
        UPDATE employees SET employment_status = 'TERMINATED', terminated_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP WHERE employee_id = #{employeeId} AND employment_status = 'ACTIVE'
        """)
    int terminate(String employeeId);

    @Update("""
        UPDATE employees SET email = #{email}, phone = #{phone}, updated_at = CURRENT_TIMESTAMP
        WHERE employee_id = #{id} AND employment_status = 'ACTIVE'
        """)
    int updateContact(@Param("id") String id, @Param("email") String email, @Param("phone") String phone);
}
