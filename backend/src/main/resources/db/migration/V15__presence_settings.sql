-- v1: durable-настройка «невидимки» (research D1): персистентна между сессиями.
ALTER TABLE users ADD COLUMN presence_hidden BOOLEAN NOT NULL DEFAULT FALSE;
