\getenv control_password LAB_CONTROL_DB_PASSWORD
CREATE ROLE control_app LOGIN PASSWORD :'control_password';
