/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.cache.infinispan.entity;

import org.hibernate.cache.CacheException;
import org.hibernate.cache.infinispan.access.InvalidationCacheAccessDelegate;
import org.hibernate.cache.infinispan.impl.BaseTransactionalDataRegion;
import org.hibernate.cache.spi.CacheDataDescription;
import org.hibernate.cache.spi.CacheKeysFactory;
import org.hibernate.cache.spi.EntityRegion;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cache.spi.access.EntityRegionAccessStrategy;

import org.infinispan.AdvancedCache;

import javax.transaction.TransactionManager;

/**
 * Entity region implementation
 *
 * @author Chris Bredesen
 * @author Galder Zamarreño
 * @since 3.5
 */
public class EntityRegionImpl extends BaseTransactionalDataRegion implements EntityRegion {
	/**
	 * Construct a entity region
	 *
	 * @param cache instance to store entity instances
	 * @param name of entity type
	 * @param transactionManager
	 * @param metadata for the entity type
	 * @param factory for the region
	 * @param cacheKeysFactory factory for cache keys
	 */
	public EntityRegionImpl(
			AdvancedCache cache, String name, TransactionManager transactionManager,
			CacheDataDescription metadata, RegionFactory factory, CacheKeysFactory cacheKeysFactory) {
		super( cache, name, transactionManager, metadata, factory, cacheKeysFactory);
	}

	@Override
	public EntityRegionAccessStrategy buildAccessStrategy(AccessType accessType) throws CacheException {
		checkAccessType(accessType);
		if ( !getCacheDataDescription().isMutable() ) {
			accessType = AccessType.READ_ONLY;
		}
		InvalidationCacheAccessDelegate accessDelegate = InvalidationCacheAccessDelegate.create(this, getValidator());
		switch ( accessType ) {
			case READ_ONLY:
				return new ReadOnlyAccess( this, accessDelegate);
			case READ_WRITE:
			case TRANSACTIONAL:
				return new ReadWriteAccess( this, accessDelegate);
			default:
				throw new CacheException( "Unsupported access type [" + accessType.getExternalName() + "]" );
		}
	}
}
