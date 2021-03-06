package org.hibernate.test.cache.infinispan;

import junit.framework.AssertionFailedError;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cache.infinispan.impl.BaseRegion;
import org.hibernate.cache.infinispan.util.Caches;
import org.hibernate.cache.internal.CacheDataDescriptionImpl;
import org.hibernate.cache.spi.CacheDataDescription;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cache.spi.access.RegionAccessStrategy;
import org.hibernate.cache.spi.access.SoftLock;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.transaction.internal.TransactionImpl;
import org.hibernate.internal.util.compare.ComparableComparator;
import org.hibernate.resource.transaction.TransactionCoordinator;
import org.hibernate.resource.transaction.backend.jdbc.internal.JdbcResourceLocalTransactionCoordinatorBuilderImpl;
import org.hibernate.resource.transaction.backend.jdbc.spi.JdbcResourceTransactionAccess;
import org.hibernate.resource.transaction.spi.TransactionCoordinatorOwner;
import org.hibernate.test.cache.infinispan.util.BatchModeJtaPlatform;
import org.hibernate.test.cache.infinispan.util.BatchModeTransactionCoordinator;
import org.hibernate.test.cache.infinispan.util.InfinispanTestingSetup;
import org.hibernate.test.cache.infinispan.util.JdbcResourceTransactionMock;
import org.hibernate.test.cache.infinispan.util.TestSynchronization;
import org.infinispan.Cache;
import org.infinispan.test.TestingUtil;
import org.jboss.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public abstract class AbstractRegionAccessStrategyTest<R extends BaseRegion, S extends RegionAccessStrategy>
		extends AbstractNonFunctionalTest {
	protected final Logger log = Logger.getLogger(getClass());

	@Rule
	public InfinispanTestingSetup infinispanTestIdentifier = new InfinispanTestingSetup();

	public static final String REGION_NAME = "test/com.foo.test";
	public static final String KEY_BASE = "KEY";
	public static final String VALUE1 = "VALUE1";
	public static final String VALUE2 = "VALUE2";
	public static final CacheDataDescription CACHE_DATA_DESCRIPTION
			= new CacheDataDescriptionImpl(true, true, ComparableComparator.INSTANCE, null);

	protected NodeEnvironment localEnvironment;
	protected R localRegion;
	protected S localAccessStrategy;

	protected NodeEnvironment remoteEnvironment;
	protected R remoteRegion;
	protected S remoteAccessStrategy;

	protected boolean transactional;
	protected boolean invalidation;
	protected boolean synchronous;
	protected Exception node1Exception;
	protected Exception node2Exception;
	protected AssertionFailedError node1Failure;
	protected AssertionFailedError node2Failure;

	protected abstract AccessType getAccessType();

	@Before
	public void prepareResources() throws Exception {
		// to mimic exactly the old code results, both environments here are exactly the same...
		StandardServiceRegistryBuilder ssrb = createStandardServiceRegistryBuilder();
		localEnvironment = new NodeEnvironment( ssrb );
		localEnvironment.prepare();

		localRegion = getRegion(localEnvironment);
		localAccessStrategy = getAccessStrategy(localRegion);

		transactional = Caches.isTransactionalCache(localRegion.getCache());
		invalidation = Caches.isInvalidationCache(localRegion.getCache());
		synchronous = Caches.isSynchronousCache(localRegion.getCache());

		// Sleep a bit to avoid concurrent FLUSH problem
		avoidConcurrentFlush();

		remoteEnvironment = new NodeEnvironment( ssrb );
		remoteEnvironment.prepare();

		remoteRegion = getRegion(remoteEnvironment);
		remoteAccessStrategy = getAccessStrategy(remoteRegion);

		waitForClusterToForm(localRegion.getCache(), remoteRegion.getCache());
	}

	private interface SessionMock extends Session, SessionImplementor {
	}

	private interface NonJtaTransactionCoordinator extends TransactionCoordinatorOwner, JdbcResourceTransactionAccess {
	}

	protected SessionImplementor mockedSession() {
		SessionMock session = mock(SessionMock.class);
		when(session.isClosed()).thenReturn(false);
		if (jtaPlatform == BatchModeJtaPlatform.class) {
			BatchModeTransactionCoordinator txCoord = new BatchModeTransactionCoordinator();
			when(session.getTransactionCoordinator()).thenReturn(txCoord);
			when(session.beginTransaction()).then(invocation -> {
				Transaction tx = txCoord.newTransaction();
				tx.begin();
				return tx;
			});
		} else if (jtaPlatform == null) {
			NonJtaTransactionCoordinator txOwner = mock(NonJtaTransactionCoordinator.class);
			when(txOwner.getResourceLocalTransaction()).thenReturn(new JdbcResourceTransactionMock());
			TransactionCoordinator txCoord = JdbcResourceLocalTransactionCoordinatorBuilderImpl.INSTANCE
							.buildTransactionCoordinator(txOwner, null);
			when(session.getTransactionCoordinator()).thenReturn(txCoord);
			when(session.beginTransaction()).then(invocation -> {
				Transaction tx = new TransactionImpl(txCoord);
				tx.begin();
				return tx;
			});
		} else {
			throw new IllegalStateException("Unknown JtaPlatform: " + jtaPlatform);
		}
		return session;
	}

	protected abstract S getAccessStrategy(R region);

	@Test
	public void testRemove() throws Exception {
		evictOrRemoveTest( false );
	}

	@Test
	public void testEvict() throws Exception {
		evictOrRemoveTest( true );
	}

	protected abstract R getRegion(NodeEnvironment environment);

	protected void waitForClusterToForm(Cache... caches) {
		TestingUtil.blockUntilViewsReceived(10000, Arrays.asList(caches));
	}

	@After
	public void releaseResources() throws Exception {
		try {
			if (localEnvironment != null) {
				localEnvironment.release();
			}
		}
		finally {
			if (remoteEnvironment != null) {
				remoteEnvironment.release();
			}
		}
	}

	protected boolean isTransactional() {
		return transactional;
	}

	protected boolean isUsingInvalidation() {
		return invalidation;
	}

	protected boolean isSynchronous() {
		return synchronous;
	}

	protected void evictOrRemoveTest(final boolean evict) throws Exception {
		final Object KEY = generateNextKey();
		assertEquals(0, localRegion.getCache().size());
		assertEquals(0, remoteRegion.getCache().size());

		assertNull("local is clean", localAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		assertNull("remote is clean", remoteAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));

		localAccessStrategy.putFromLoad(mockedSession(), KEY, VALUE1, System.currentTimeMillis(), new Integer(1));
		assertEquals(VALUE1, localAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		remoteAccessStrategy.putFromLoad(mockedSession(), KEY, VALUE1, System.currentTimeMillis(), new Integer(1));
		assertEquals(VALUE1, remoteAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));

		SessionImplementor session = mockedSession();
		withTx(localEnvironment, session, () -> {
			if (evict) {
				localAccessStrategy.evict(KEY);
			}
			else {
				doRemove(localRegion.getTransactionManager(), localAccessStrategy, session, KEY);
			}
			return null;
		});

		assertNull(localAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		assertEquals(0, localRegion.getCache().size());
		assertNull(remoteAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		assertEquals(0, remoteRegion.getCache().size());
	}

	protected void doRemove(TransactionManager tm, S strategy, SessionImplementor session, Object key) throws SystemException, RollbackException {
		SoftLock softLock = strategy.lockItem(session, key, null);
		strategy.remove(session, key);
		session.getTransactionCoordinator().getLocalSynchronizations().registerSynchronization(
				new TestSynchronization.UnlockItem(strategy, session, key, softLock));
	}

	@Test
	public void testRemoveAll() throws Exception {
		evictOrRemoveAllTest(false);
	}

	@Test
	public void testEvictAll() throws Exception {
		evictOrRemoveAllTest(true);
	}

	protected void assertThreadsRanCleanly() {
		if (node1Failure != null) {
			throw node1Failure;
		}
		if (node2Failure != null) {
			throw node2Failure;
		}

		if (node1Exception != null) {
			log.error("node1 saw an exception", node1Exception);
			assertEquals("node1 saw no exceptions", null, node1Exception);
		}

		if (node2Exception != null) {
			log.error("node2 saw an exception", node2Exception);
			assertEquals("node2 saw no exceptions", null, node2Exception);
		}
	}

	@Test
	public void testPutFromLoad() throws Exception {
		putFromLoadTest( false );
	}

	@Test
	public void testPutFromLoadMinimal() throws Exception {
		putFromLoadTest( true );
	}

	protected abstract void putFromLoadTest(boolean useMinimalAPI) throws Exception;

	protected abstract Object generateNextKey();

	protected void evictOrRemoveAllTest(final boolean evict) throws Exception {
		final Object KEY = generateNextKey();
		assertEquals(0, localRegion.getCache().size());
		assertEquals(0, remoteRegion.getCache().size());
		assertNull("local is clean", localAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		assertNull("remote is clean", remoteAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));

		localAccessStrategy.putFromLoad(mockedSession(), KEY, VALUE1, System.currentTimeMillis(), new Integer(1));
		assertEquals(VALUE1, localAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		remoteAccessStrategy.putFromLoad(mockedSession(), KEY, VALUE1, System.currentTimeMillis(), new Integer(1));
		assertEquals(VALUE1, remoteAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));

		// Wait for async propagation
		sleep(250);

		withTx(localEnvironment, mockedSession(), () -> {
			if (evict) {
				localAccessStrategy.evictAll();
			} else {
				SoftLock softLock = localAccessStrategy.lockRegion();
				localAccessStrategy.removeAll();
				localAccessStrategy.unlockRegion(softLock);
			}
			return null;
		});
		// This should re-establish the region root node in the optimistic case
		assertNull(localAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		assertEquals(0, localRegion.getCache().size());

		// Re-establishing the region root on the local node doesn't
		// propagate it to other nodes. Do a get on the remote node to re-establish
		assertNull(remoteAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		assertEquals(0, remoteRegion.getCache().size());

		// Wait for async propagation of EndInvalidationCommand before executing naked put
		sleep(250);

		// Test whether the get above messes up the optimistic version
		remoteAccessStrategy.putFromLoad(mockedSession(), KEY, VALUE1, System.currentTimeMillis(), new Integer(1));
		assertEquals(VALUE1, remoteAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		assertEquals(1, remoteRegion.getCache().size());

		// Wait for async propagation
		sleep(250);

		assertEquals((isUsingInvalidation() ? null : VALUE1), localAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
		assertEquals(VALUE1, remoteAccessStrategy.get(mockedSession(), KEY, System.currentTimeMillis()));
	}

	protected class PutFromLoadNode2 extends Thread {
		private final Object KEY;
		private final CountDownLatch writeLatch1;
		private final CountDownLatch writeLatch2;
		private final boolean useMinimalAPI;
		private final CountDownLatch completionLatch;

		public PutFromLoadNode2(Object KEY, CountDownLatch writeLatch1, CountDownLatch writeLatch2, boolean useMinimalAPI, CountDownLatch completionLatch) {
			this.KEY = KEY;
			this.writeLatch1 = writeLatch1;
			this.writeLatch2 = writeLatch2;
			this.useMinimalAPI = useMinimalAPI;
			this.completionLatch = completionLatch;
		}

		@Override
		public void run() {
			try {
				long txTimestamp = System.currentTimeMillis();
				SessionImplementor session = mockedSession();
				withTx(remoteEnvironment, session, () -> {

					assertNull(remoteAccessStrategy.get(session, KEY, txTimestamp));

					// Let node1 write
					writeLatch1.countDown();
					// Wait for node1 to finish
					writeLatch2.await();

					if (useMinimalAPI) {
						remoteAccessStrategy.putFromLoad(session, KEY, VALUE1, txTimestamp, new Integer(1), true);
					} else {
						remoteAccessStrategy.putFromLoad(session, KEY, VALUE1, txTimestamp, new Integer(1));
					}
					return null;
				});
			} catch (Exception e) {
				log.error("node2 caught exception", e);
				node2Exception = e;
			} catch (AssertionFailedError e) {
				node2Failure = e;
			} finally {
				completionLatch.countDown();
			}
		}
	}
}
