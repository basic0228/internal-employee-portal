-- Read-only preparation check. Does not read account passwords or employee personal data.
SELECT current_database() AS database_name,
       current_schema() AS schema_name,
       to_regclass('public.employees') AS employees_table,
       to_regclass('public.user_accounts') AS accounts_table;

SELECT table_schema, table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('employees', 'user_accounts')
ORDER BY table_name, ordinal_position;
