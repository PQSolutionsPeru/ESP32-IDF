-- HDD-Monitor PostgreSQL Redundancy Setup
-- Run as postgres user: sudo -u postgres psql < setup_postgresql.sql

CREATE DATABASE hdd_monitor;
CREATE USER hdd_monitor_user WITH PASSWORD 'secure_password';
GRANT ALL PRIVILEGES ON DATABASE hdd_monitor TO hdd_monitor_user;

\c hdd_monitor

CREATE TABLE relay_events (
    id SERIAL PRIMARY KEY,
    client_id VARCHAR(50) NOT NULL,
    panel_id VARCHAR(50) NOT NULL,
    relay_id VARCHAR(20) NOT NULL,
    old_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    timestamp TIMESTAMP DEFAULT NOW(),
    source VARCHAR(20) DEFAULT 'mqtt',
    firestore_synced BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_relay_events_timestamp ON relay_events(timestamp DESC);
CREATE INDEX idx_relay_events_panel ON relay_events(client_id, panel_id);

CREATE TABLE connectivity_events (
    id SERIAL PRIMARY KEY,
    esp32_id VARCHAR(20) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    time_range VARCHAR(50),
    timestamp TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_connectivity_events_timestamp ON connectivity_events(timestamp DESC);
CREATE INDEX idx_connectivity_events_esp32 ON connectivity_events(esp32_id);

-- Grant permissions
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO hdd_monitor_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO hdd_monitor_user;
