import time
import structlog
from typing import Dict

logger = structlog.get_logger()

class NFPAMetricsCollector:
    def __init__(self):
        self.relay_event_timestamps = {}

    def record_relay_detected(self, event_id: str):
        self.relay_event_timestamps[event_id] = {
            'detected': time.time() * 1000,
            'notified': None
        }

    def record_notification_sent(self, event_id: str):
        if event_id in self.relay_event_timestamps:
            self.relay_event_timestamps[event_id]['notified'] = time.time() * 1000

            detected = self.relay_event_timestamps[event_id]['detected']
            notified = self.relay_event_timestamps[event_id]['notified']
            latency_ms = notified - detected

            compliant = latency_ms < 90000  # NFPA 72: <90s

            logger.info("nfpa72_notification_latency",
                       event_id=event_id,
                       latency_ms=latency_ms,
                       nfpa72_compliant=compliant)

            if not compliant:
                logger.error("nfpa72_violation",
                            event_id=event_id,
                            exceeded_by_ms=latency_ms - 90000)

            # Limpiar evento antiguo
            del self.relay_event_timestamps[event_id]
