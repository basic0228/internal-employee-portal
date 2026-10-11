-- Apply once after V1/V2. Adds nullable fields without replacing employee data.
ALTER TABLE employees ADD COLUMN email VARCHAR(254);
ALTER TABLE employees ADD COLUMN phone VARCHAR(32);
