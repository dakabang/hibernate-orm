/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.test.cache.infinispan.collection;

import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cache.spi.access.CollectionRegionAccessStrategy;
import org.hibernate.test.cache.infinispan.AbstractExtraAPITest;
import org.hibernate.test.cache.infinispan.util.TestInfinispanRegionFactory;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * @author Galder Zamarreño
 * @since 3.5
 */
public abstract class CollectionRegionAccessExtraAPITest extends AbstractExtraAPITest<CollectionRegionAccessStrategy> {
	@Override
	protected CollectionRegionAccessStrategy getAccessStrategy() {
		return environment.getCollectionRegion( REGION_NAME, CACHE_DATA_DESCRIPTION).buildAccessStrategy( getAccessType() );
	}

	public static class Transactional extends CollectionRegionAccessExtraAPITest {
		@Override
		protected AccessType getAccessType() {
			return AccessType.TRANSACTIONAL;
		}

		@Override
		protected Class<? extends RegionFactory> getRegionFactoryClass() {
			return TestInfinispanRegionFactory.Transactional.class;
		}
	}

	public static class ReadWrite extends CollectionRegionAccessExtraAPITest {
		@Override
		protected AccessType getAccessType() {
			return AccessType.READ_WRITE;
		}
	}

	public static class ReadOnly extends CollectionRegionAccessExtraAPITest {
		@Override
		protected AccessType getAccessType() {
			return AccessType.READ_ONLY;
		}
	}
}
