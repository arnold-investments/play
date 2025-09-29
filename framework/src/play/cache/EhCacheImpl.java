package play.cache;

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.expiry.ExpiryPolicy;
import org.ehcache.impl.config.copy.DefaultCopierConfiguration;
import org.ehcache.impl.copy.IdentityCopier;
import play.Logger;

import java.io.Serializable;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * EhCache implementation.
 *
 * <p>Ehcache is an open source, standards-based cache used to boost performance,
 * offload the database and simplify scalability. Ehcache is robust, proven and
 * full-featured, and this has made it the most widely used Java-based cache.</p>
 *
 * Expiration is specified in seconds
 * 
 * @see <a href="http://ehcache.org/">http://ehcache.org/</a>
 *
 */
public class EhCacheImpl implements CacheImpl {
	public record Element(Object value, int timeToLive) implements Serializable {}

	private static final String cacheName = "play";
	private static EhCacheImpl uniqueInstance;

	final CacheManager cacheManager = CacheManagerBuilder.newCacheManagerBuilder().build(true);

	final Cache<String, Element> cache;

	/*
		<defaultCache
			maxElementsInMemory="10000"
			eternal="false"
			timeToIdleSeconds="120"
			timeToLiveSeconds="120"
			overflowToDisk="false"
			maxElementsOnDisk="10000000"
			diskPersistent="false"
			diskExpiryThreadIntervalSeconds="120"
			memoryStoreEvictionPolicy="LRU"
	/>
	 */

	private EhCacheImpl() {
		this.cacheManager.createCache(
			cacheName,
			CacheConfigurationBuilder.newCacheConfigurationBuilder(
				String.class,
				Element.class,
				ResourcePoolsBuilder.heap(10000)
			)

				.withExpiry(new ExpiryPolicy<>() {
					@Override
					public Duration getExpiryForCreation(String key, Element value) {
						return Duration.ofSeconds(value.timeToLive());
					}

					@Override
					public Duration getExpiryForAccess(String key, Supplier<? extends Element> value) {
						Element v = value.get();

						return v == null ? null : Duration.ofSeconds(v.timeToLive());
					}

					@Override
					public Duration getExpiryForUpdate(String key, Supplier<? extends Element> oldValue, Element newValue) {
						return Duration.ofSeconds(newValue.timeToLive());
					}
				})

				.withService(new DefaultCopierConfiguration<>(IdentityCopier.identityCopier(), DefaultCopierConfiguration.Type.VALUE))
		);

		this.cache = cacheManager.getCache(cacheName, String.class, Element.class);
	}

	public static EhCacheImpl getInstance() {
		return uniqueInstance;
	}

	public static EhCacheImpl newInstance() {
		uniqueInstance = new EhCacheImpl();
		return uniqueInstance;
	}

	@Override
	public void add(String key, Object value, int expiration) {
		if (cache.containsKey(key)) {
			return;
		}

		cache.put(key, new Element(value, expiration));
	}

	@Override
	public void clear() {
		cache.clear();
	}

	@Override
	public synchronized long decr(String key, int by) {
		Element e = cache.get(key);
		if (e == null) {
			return -1;
		}

		long newValue = ((Number) e.value()).longValue() - by;
		Element newE = new Element(newValue, e.timeToLive());
		cache.put(key, newE);
		return newValue;
	}

	@Override
	public void delete(String key) {
		cache.remove(key);
	}

	@Override
	public Object get(String key) {
		Element e = cache.get(key);
		return (e == null) ? null : e.value();
	}

	@Override
	public Map<String, Object> get(String[] keys) {
		Map<String, Object> result = new HashMap<>(keys.length);
		for (String key : keys) {
			result.put(key, get(key));
		}
		return result;
	}

	@Override
	public synchronized long incr(String key, int by) {
		Element e = cache.get(key);
		if (e == null) {
			return -1;
		}
		long newValue = ((Number) e.value()).longValue() + by;
		Element newE = new Element(newValue, e.timeToLive());
		cache.put(key, newE);
		return newValue;

	}

	@Override
	public void replace(String key, Object value, int expiration) {
		if (cache.containsKey(key)) {
			return;
		}
		Element element = new Element(value, expiration);
		cache.put(key, element);
	}

	@Override
	public boolean safeAdd(String key, Object value, int expiration) {
		try {
			add(key, value, expiration);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public boolean safeDelete(String key) {
		try {
			delete(key);
			return true;
		} catch (Exception e) {
			Logger.error(e.toString());
			return false;
		}
	}

	@Override
	public boolean safeReplace(String key, Object value, int expiration) {
		try {
			replace(key, value, expiration);
			return true;
		} catch (Exception e) {
			Logger.error(e.toString());
			return false;
		}
	}

	@Override
	public boolean safeSet(String key, Object value, int expiration) {
		try {
			set(key, value, expiration);
			return true;
		} catch (Exception e) {
			Logger.error(e.toString());
			return false;
		}
	}

	@Override
	public void set(String key, Object value, int expiration) {
		Element element = new Element(value, expiration);
		cache.put(key, element);
	}

	@Override
	public void stop() {
		cacheManager.close();
	}
}
