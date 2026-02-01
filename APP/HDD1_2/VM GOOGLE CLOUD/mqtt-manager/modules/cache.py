"""
Simple in-memory cache with TTL and LRU eviction
"""

from datetime import datetime, timedelta
from functools import wraps
from typing import Any, Callable, Optional
import logging

logger = logging.getLogger(__name__)


class SimpleCache:
    """Simple in-memory cache with TTL and LRU eviction"""

    def __init__(self, max_size: int = 100):
        """
        Initialize cache

        Args:
            max_size: Maximum number of entries in cache
        """
        self.cache = {}
        self.max_size = max_size
        self.access_order = []

    def get(self, key: str) -> Optional[Any]:
        """
        Get value from cache

        Args:
            key: Cache key

        Returns:
            Cached value or None if not found or expired
        """
        if key not in self.cache:
            return None

        entry = self.cache[key]
        expires_at = entry['expires_at']

        # Check if expired
        if expires_at and datetime.utcnow() > expires_at:
            del self.cache[key]
            if key in self.access_order:
                self.access_order.remove(key)
            return None

        # Update access order (LRU)
        if key in self.access_order:
            self.access_order.remove(key)
        self.access_order.append(key)

        return entry['value']

    def set(self, key: str, value: Any, ttl: int = 60):
        """
        Set value in cache

        Args:
            key: Cache key
            value: Value to cache
            ttl: Time to live in seconds (0 = no expiration)
        """
        # Calculate expiration time
        if ttl > 0:
            expires_at = datetime.utcnow() + timedelta(seconds=ttl)
        else:
            expires_at = None

        # Evict oldest entry if cache is full
        if len(self.cache) >= self.max_size and key not in self.cache:
            if self.access_order:
                oldest_key = self.access_order.pop(0)
                if oldest_key in self.cache:
                    del self.cache[oldest_key]

        # Store entry
        self.cache[key] = {
            'value': value,
            'expires_at': expires_at,
            'created_at': datetime.utcnow()
        }

        # Update access order
        if key in self.access_order:
            self.access_order.remove(key)
        self.access_order.append(key)

    def clear(self):
        """Clear all cache entries"""
        self.cache.clear()
        self.access_order.clear()
        logger.info("Cache cleared")

    def get_stats(self) -> dict:
        """Get cache statistics"""
        return {
            'size': len(self.cache),
            'max_size': self.max_size,
            'keys': list(self.cache.keys())
        }


def cached(ttl: int = 60):
    """
    Decorator for caching function results

    Args:
        ttl: Time to live in seconds

    Usage:
        @cached(ttl=30)
        def expensive_function(arg1, arg2):
            # ... expensive computation
            return result
    """
    def decorator(func: Callable) -> Callable:
        cache = SimpleCache(max_size=50)

        @wraps(func)
        def wrapper(*args, **kwargs):
            # Create cache key from function name and arguments
            cache_key = f"{func.__name__}:{str(args)}:{str(kwargs)}"

            # Try to get from cache
            cached_value = cache.get(cache_key)
            if cached_value is not None:
                logger.debug(f"Cache hit for {func.__name__}")
                return cached_value

            # Call function and cache result
            logger.debug(f"Cache miss for {func.__name__}")
            result = func(*args, **kwargs)
            cache.set(cache_key, result, ttl=ttl)
            return result

        # Add cache management methods
        wrapper.cache_clear = cache.clear
        wrapper.cache_stats = cache.get_stats

        return wrapper
    return decorator
