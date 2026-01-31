import redis
import time
from enum import Enum
import logging

class EventPriority(Enum):
    CRITICAL = 0   # Relay events - SIN LÍMITE
    HIGH = 1       # ESP32 offline - 10/min
    MEDIUM = 2     # Connectivity - 2/10min
    LOW = 3        # Heartbeats - 1/30s

class RateLimiter:
    def __init__(self, redis_host='localhost', redis_port=6379):
        self.redis = redis.Redis(host=redis_host, port=redis_port, decode_responses=True)

        # (eventos_max, ventana_segundos)
        self.limits = {
            EventPriority.CRITICAL: (float('inf'), 1),
            EventPriority.HIGH: (10, 60),
            EventPriority.MEDIUM: (2, 600),
            EventPriority.LOW: (1, 30)
        }

    def check_and_increment(self, event_key: str, priority: EventPriority) -> bool:
        """Retorna True si puede procesar, False si excede límite."""
        if priority == EventPriority.CRITICAL:
            return True  # Relay events SIEMPRE pasan

        max_count, window_seconds = self.limits[priority]
        key = f"ratelimit:{priority.name}:{event_key}"

        current = self.redis.get(key)

        if current is None:
            self.redis.setex(key, window_seconds, 1)
            return True

        count = int(current)
        if count >= max_count:
            logging.warning(f"Rate limit exceeded: {event_key} ({priority.name})")
            return False

        self.redis.incr(key)
        return True
