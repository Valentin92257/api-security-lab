CREATE INDEX access_logs_page ON detector.access_logs
    (event_time DESC, event_nanos DESC, run_id DESC, producer DESC, sequence DESC);
