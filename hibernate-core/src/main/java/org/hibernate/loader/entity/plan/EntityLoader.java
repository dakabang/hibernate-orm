/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.loader.entity.plan;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.MappingException;
import org.hibernate.engine.spi.LoadQueryInfluencers;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.CoreLogging;
import org.hibernate.loader.plan.exec.query.spi.QueryBuildingParameters;
import org.hibernate.persister.entity.OuterJoinLoadable;
import org.hibernate.type.Type;

import org.jboss.logging.Logger;

/**
 * UniqueEntityLoader implementation that is the main functionality for LoadPlan-based Entity loading.
 * <p/>
 * Can handle batch-loading as well as non-pk, unique-key loading,
 * <p/>
 * Much is ultimately delegated to its superclass, AbstractLoadPlanBasedEntityLoader.  However:
 *
 * Loads an entity instance using outerjoin fetching to fetch associated entities.
 * <br>
 * The <tt>EntityPersister</tt> must implement <tt>Loadable</tt>. For other entities,
 * create a customized subclass of <tt>Loader</tt>.
 *
 * @author Gavin King
 * @author Steve Ebersole
 * @author Gail Badner
 */
public class EntityLoader extends AbstractLoadPlanBasedEntityLoader  {
	private static final Logger log = CoreLogging.logger( EntityLoader.class );

	public static Builder forEntity(OuterJoinLoadable persister) {
		return new Builder( persister );
	}

	public static class Builder {
		private final OuterJoinLoadable persister;
		private int batchSize = 1;
		private LoadQueryInfluencers influencers = LoadQueryInfluencers.NONE;
		private LockMode lockMode = LockMode.NONE;
		private LockOptions lockOptions;

		public Builder(OuterJoinLoadable persister) {
			this.persister = persister;
		}

		public Builder withBatchSize(int batchSize) {
			this.batchSize = batchSize;
			return this;
		}

		public Builder withInfluencers(LoadQueryInfluencers influencers) {
			this.influencers = influencers;
			return this;
		}

		public Builder withLockMode(LockMode lockMode) {
			this.lockMode = lockMode;
			return this;
		}

		public Builder withLockOptions(LockOptions lockOptions) {
			this.lockOptions = lockOptions;
			return this;
		}

		public EntityLoader byPrimaryKey() {
			return byUniqueKey( persister.getIdentifierColumnNames(), persister.getIdentifierType() );
		}

		public EntityLoader byUniqueKey(String[] keyColumnNames, Type keyType) {
			return new EntityLoader(
					persister.getFactory(),
					persister,
					keyColumnNames,
					keyType,
					new QueryBuildingParameters() {
						@Override
						public LoadQueryInfluencers getQueryInfluencers() {
							return influencers;
						}

						@Override
						public int getBatchSize() {
							return batchSize;
						}

						@Override
						public LockMode getLockMode() {
							return lockMode;
						}

						@Override
						public LockOptions getLockOptions() {
							return lockOptions;
						}
					}
			);
		}
	}

	private EntityLoader(
			SessionFactoryImplementor factory,
			OuterJoinLoadable persister,
			String[] uniqueKeyColumnNames,
			Type uniqueKeyType,
			QueryBuildingParameters buildingParameters) throws MappingException {
		super( persister, factory, uniqueKeyColumnNames, uniqueKeyType, buildingParameters );
		if ( log.isDebugEnabled() ) {
			if ( buildingParameters.getLockOptions() != null ) {
				log.debugf(
						"Static select for entity %s [%s:%s]: %s",
						getEntityName(),
						buildingParameters.getLockOptions().getLockMode(),
						buildingParameters.getLockOptions().getTimeOut(),
						getStaticLoadQuery().getSqlStatement()
				);
			}
			else if ( buildingParameters.getLockMode() != null ) {
				log.debugf(
						"Static select for entity %s [%s]: %s",
						getEntityName(),
						buildingParameters.getLockMode(),
						getStaticLoadQuery().getSqlStatement()
				);
			}
		}
	}
}
