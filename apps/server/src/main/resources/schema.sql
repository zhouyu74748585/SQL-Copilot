CREATE TABLE IF NOT EXISTS connection_info (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    db_type TEXT NOT NULL,
    host TEXT,
    port INTEGER,
    database_name TEXT,
    username TEXT,
    password TEXT,
    auth_type TEXT,
    env TEXT NOT NULL,
    read_only INTEGER NOT NULL DEFAULT 0,
    ssh_enabled INTEGER NOT NULL DEFAULT 0,
    ssh_host TEXT,
    ssh_port INTEGER,
    ssh_user TEXT,
    last_test_status TEXT,
    last_test_message TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS query_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    connection_id INTEGER NOT NULL,
    session_id TEXT,
    prompt_text TEXT,
    sql_text TEXT NOT NULL,
    execution_ms INTEGER,
    success_flag INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    connection_id INTEGER NOT NULL,
    session_id TEXT,
    risk_level TEXT,
    sql_digest TEXT,
    operator_name TEXT,
    action TEXT,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_provider_config (
    id INTEGER PRIMARY KEY,
    provider_type TEXT NOT NULL,
    openai_base_url TEXT,
    openai_api_key TEXT,
    openai_model TEXT,
    cli_command TEXT,
    cli_args TEXT,
    cli_working_dir TEXT,
    updated_at INTEGER NOT NULL
);
