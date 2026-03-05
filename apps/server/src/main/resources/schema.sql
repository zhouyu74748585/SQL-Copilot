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
    ssh_auth_type TEXT,
    ssh_password TEXT,
    ssh_private_key_path TEXT,
    ssh_private_key_text TEXT,
    ssh_private_key_passphrase TEXT,
    selected_databases_json TEXT,
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
    history_type TEXT DEFAULT 'CHAT',
    action_type TEXT,
    assistant_content TEXT,
    database_name TEXT,
    chart_config_json TEXT,
    chart_image_cache_key TEXT,
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
    cli_working_dir TEXT,
    model_options_json TEXT,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rag_embedding_config (
    id INTEGER PRIMARY KEY,
    rag_embedding_model_dir TEXT,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rag_vectorize_status (
    connection_id INTEGER NOT NULL,
    database_name TEXT NOT NULL,
    status TEXT NOT NULL,
    message TEXT,
    updated_at INTEGER NOT NULL,
    last_full_vectorize_duration_ms INTEGER,
    last_full_vectorize_provider TEXT,
    PRIMARY KEY(connection_id, database_name)
);
