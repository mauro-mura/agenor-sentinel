-- Targets served by the sentinel-fixtures container. Each one is switched on and off on command.
INSERT INTO target (name, url, interval_seconds, threshold) VALUES
    ('alpha',   'http://fixtures:8080/t/alpha',   10, 3),
    ('bravo',   'http://fixtures:8080/t/bravo',   10, 3),
    ('charlie', 'http://fixtures:8080/t/charlie', 15, 3),
    ('delta',   'http://fixtures:8080/t/delta',   15, 2),
    ('echo',    'http://fixtures:8080/t/echo',    30, 2);
