package com.bit.portal.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AccountMapper {
    String QUERY = """
        SELECT a.id, a.employee_id, a.username, a.password_hash, a.role, a.enabled, e.employment_status
        FROM user_accounts a LEFT JOIN employees e ON e.employee_id = a.employee_id
        """;

    @Select(QUERY + " WHERE a.username = #{username}")
    Account findByUsername(String username);

    @Select(QUERY + " WHERE a.id = #{id}")
    Account findById(long id);

    @Select("SELECT COUNT(*) FROM user_accounts WHERE role = 'ADMIN'")
    int countAdmins();

    @Insert("""
        INSERT INTO user_accounts (employee_id, username, password_hash, role)
        VALUES (#{employeeId}, #{username}, #{hash}, #{role})
        """)
    int insert(@Param("employeeId") String employeeId, @Param("username") String username,
            @Param("hash") String hash, @Param("role") String role);

    @Update("UPDATE user_accounts SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP WHERE employee_id = #{id}")
    int disableEmployee(String id);
}
