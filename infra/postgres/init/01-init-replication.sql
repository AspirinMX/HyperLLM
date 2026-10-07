-- Executed automatically on initial PostgreSQL container initialization.
DO $$
BEGIN
    RAISE NOTICE 'HyperLLM Postgres initialized with wal_level = %', current_setting('wal_level');
END $$;
