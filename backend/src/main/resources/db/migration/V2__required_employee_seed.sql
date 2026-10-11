-- Required assignment data. A conflicting employee is never overwritten.
INSERT INTO employees (employee_id, full_name, date_of_birth) VALUES
('EMP-001', '김민준', DATE '1990-03-15'),
('EMP-002', '김민준', DATE '1994-11-02'),
('EMP-003', '남궁서준', DATE '1988-07-21'),
('EMP-004', '황보라온', DATE '1995-02-09'),
('EMP-005', '김솔', DATE '1992-12-30'),
('EMP-006', '선우진', DATE '1991-05-05'),
('EMP-007', '이서연', NULL),
('EMP-008', '박민준', DATE '1993-08-17'),
('EMP-009', '최지우', DATE '1996-04-03'),
('EMP-010', '정하윤', DATE '1989-10-11')
ON CONFLICT DO NOTHING;
