/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.test.cache.infinispan;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cache.infinispan.util.Caches;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.transaction.jta.platform.spi.JtaPlatform;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.hibernate.test.cache.infinispan.util.BatchModeJtaPlatform;
import org.hibernate.test.cache.infinispan.util.CacheTestUtil;
import org.hibernate.test.cache.infinispan.util.InfinispanTestingSetup;
import org.hibernate.test.cache.infinispan.util.TestInfinispanRegionFactory;
import org.hibernate.testing.junit4.CustomParameterized;
import org.infinispan.Cache;
import org.jboss.logging.Logger;
import org.junit.After;
import org.junit.Before;

import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.test.cache.infinispan.util.CacheTestSupport;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.transaction.TransactionManager;

/**
 * Base class for all non-functional tests of Infinispan integration.
 *
 * @author Galder Zamarreño
 * @since 3.5
 */
@RunWith(CustomParameterized.class)
public abstract class AbstractNonFunctionalTest extends org.hibernate.testing.junit4.BaseUnitTestCase {
	private static final Logger log = Logger.getLogger(AbstractNonFunctionalTest.class);

	@Rule
	public InfinispanTestingSetup infinispanTestIdentifier = new InfinispanTestingSetup();

	@Parameterized.Parameters(name = "{0}")
	public static List<Object[]> getJtaParameters() {
		return Arrays.asList(
				new Object[] { "JTA", BatchModeJtaPlatform.class },
				new Object[] { "non-JTA", null });
	}

	@Parameterized.Parameter(value = 0)
	public String mode;

	@Parameterized.Parameter(value = 1)
	public Class<? extends JtaPlatform> jtaPlatform;

	public static final String REGION_PREFIX = "test";

	private static final String PREFER_IPV4STACK = "java.net.preferIPv4Stack";
	private String preferIPv4Stack;
	private static final String JGROUPS_CFG_FILE = "hibernate.cache.infinispan.jgroups_cfg";
	private String jgroupsCfgFile;

	private CacheTestSupport testSupport = new CacheTestSupport();

	@Before
	public void prepareCacheSupport() throws Exception {
		preferIPv4Stack = System.getProperty(PREFER_IPV4STACK);
		System.setProperty(PREFER_IPV4STACK, "true");
		jgroupsCfgFile = System.getProperty(JGROUPS_CFG_FILE);
		System.setProperty(JGROUPS_CFG_FILE, "2lc-test-tcp.xml");

		testSupport.setUp();
	}

	@After
	public void releaseCachSupport() throws Exception {
		testSupport.tearDown();

		if (preferIPv4Stack == null) {
			System.clearProperty(PREFER_IPV4STACK);
		} else {
			System.setProperty(PREFER_IPV4STACK, preferIPv4Stack);
		}

		if (jgroupsCfgFile == null)
			System.clearProperty(JGROUPS_CFG_FILE);
		else
			System.setProperty(JGROUPS_CFG_FILE, jgroupsCfgFile);
	}

	protected <T> T withTx(NodeEnvironment environment, SessionImplementor session, Callable<T> callable) throws Exception {
		if (jtaPlatform != null) {
			TransactionManager tm = environment.getServiceRegistry().getService(JtaPlatform.class).retrieveTransactionManager();
			return Caches.withinTx(tm, callable);
		} else {
			Transaction transaction = ((Session) session).beginTransaction();
			boolean rollingBack = false;
			try {
				T retval = callable.call();
				if (transaction.getStatus() == TransactionStatus.ACTIVE) {
					transaction.commit();
				} else {
					rollingBack = true;
					transaction.rollback();
				}
				return retval;
			} catch (Exception e) {
				if (!rollingBack) {
					try {
						transaction.rollback();
					} catch (Exception suppressed) {
						e.addSuppressed(suppressed);
					}
				}
				throw e;
			}
		}
	}

	protected void registerCache(Cache cache) {
		testSupport.registerCache(cache);
	}

	protected void unregisterCache(Cache cache) {
		testSupport.unregisterCache(cache);
	}

	protected void registerFactory(RegionFactory factory) {
		testSupport.registerFactory(factory);
	}

	protected void unregisterFactory(RegionFactory factory) {
		testSupport.unregisterFactory(factory);
	}

	protected CacheTestSupport getCacheTestSupport() {
		return testSupport;
	}

	protected void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			log.warn("Interrupted during sleep", e);
		}
	}

	protected void avoidConcurrentFlush() {
		testSupport.avoidConcurrentFlush();
	}

	protected StandardServiceRegistryBuilder createStandardServiceRegistryBuilder() {
		final StandardServiceRegistryBuilder ssrb = CacheTestUtil.buildBaselineStandardServiceRegistryBuilder(
				REGION_PREFIX, getRegionFactoryClass(), true, false, jtaPlatform);
		return ssrb;
	}

	protected Class<? extends RegionFactory> getRegionFactoryClass() {
		return TestInfinispanRegionFactory.class;
	}
}