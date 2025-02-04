/**
 * Copyright (c) 2018-present, A2 Rešitve d.o.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package solutions.a2.cdc.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleStatement;
import oracle.jdbc.pool.OracleDataSource;
import oracle.ucp.UniversalConnectionPoolException;
import oracle.ucp.admin.UniversalConnectionPoolManager;
import oracle.ucp.admin.UniversalConnectionPoolManagerImpl;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

/**
 * 
 * OraConnectionObjects: OracleUCP pool container
 * 
 * @author <a href="mailto:averemee@a2.solutions">Aleksei Veremeev</a>
 */
public class OraConnectionObjects {

	private static final Logger LOGGER = LoggerFactory.getLogger(OraConnectionObjects.class);
	private static final int INITIAL_SIZE = 4;
	private static final AtomicBoolean state = new AtomicBoolean(true);
	private static final AtomicInteger taskId = new AtomicInteger(0);

	private PoolDataSource pds;
	private final String poolName;
	private boolean standby = false;
	private boolean distributed = false;
	private Connection connection4LogMiner;
	private String auxDbUrl, auxWallet;
	private int version = 0;

	private OraConnectionObjects(final String poolName, final String dbUrl) throws SQLException {
		this.poolName = poolName;
		pds = PoolDataSourceFactory.getPoolDataSource();
		pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
		pds.setConnectionPoolName(poolName);
		pds.setURL(dbUrl);
		pds.setInitialPoolSize(INITIAL_SIZE);
		pds.setMinPoolSize(INITIAL_SIZE);
	}

	public static OraConnectionObjects get4UserPassword(final String poolName,
			final String dbUrl, final String dbUser, final String dbPassword)
					throws SQLException {
		OraConnectionObjects oco = new OraConnectionObjects(poolName, dbUrl);
		oco.setUserPassword(dbUser, dbPassword);
		return oco;
	}

	public static OraConnectionObjects get4OraWallet(final String poolName,
			final String dbUrl, final String wallet)
					throws SQLException {
		System.setProperty(OracleConnection.CONNECTION_PROPERTY_WALLET_LOCATION, wallet);
		OraConnectionObjects oco = new OraConnectionObjects(poolName, dbUrl);
		return oco;
	}

	public static OraConnectionObjects get4UserPassword(final String poolName,
			final List<String> dbUrls, final String dbUser, final String dbPassword)
					throws SQLException {
		return get4Rac(poolName, dbUrls, dbUser, dbPassword, null);
	}

	public static OraConnectionObjects get4OraWallet(final String poolName,
			final List<String> dbUrls, final String wallet)
					throws SQLException {
		return get4Rac(poolName, dbUrls, null, null, wallet);
	}

	private static OraConnectionObjects get4Rac(final String poolName,
			final List<String> dbUrls, final String dbUser, final String dbPassword,
			final String wallet) throws SQLException {
		while (!state.compareAndSet(true, false)) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
			}
		}
		final int index = taskId.getAndAdd(1);
		if (index > (dbUrls.size() - 1)) {
			LOGGER.error("Errors while processing following array of Oracle RAC URLs:");
			dbUrls.forEach(v -> LOGGER.error("\t{}", v));
			LOGGER.error("Size equals {}, but current index equals {} !", dbUrls.size(), index);
			throw new ConnectException("Unable to build connections to Oracle RAC!");
		} else if (index == (dbUrls.size() - 1)) {
			// Last element - reset back to 0
			taskId.set(0);
		}
		LOGGER.debug("Processing URL array element {} with value {}.",
				index, dbUrls.get(index));
		if (wallet != null) {
			System.setProperty(OracleConnection.CONNECTION_PROPERTY_WALLET_LOCATION, wallet);
		}
		OraConnectionObjects oco = new OraConnectionObjects(poolName + "-" + index, dbUrls.get(index));
		if (wallet == null) {
			oco.setUserPassword(dbUser, dbPassword);
		}
		state.set(true);
		return oco;
	}

	private void setUserPassword(final String dbUser, final String dbPassword) throws SQLException {
		pds.setUser(dbUser);
		pds.setPassword(dbPassword);
	}

	public void addStandbyConnection(final String dbUrl, final String wallet)
					throws SQLException {
		initConnection4LogMiner(true, dbUrl, wallet);
	}

	public void addDistributedConnection(final String dbUrl, final String wallet)
					throws SQLException {
		initConnection4LogMiner(false, dbUrl, wallet);
	}

	private void initConnection4LogMiner(
			final boolean init4Standby, final String dbUrl, final String wallet)
					throws SQLException {
		final String dbType = init4Standby ? "standby" : "target mining";
		final Properties props = new Properties();
		if (init4Standby) {
			props.setProperty(OracleConnection.CONNECTION_PROPERTY_INTERNAL_LOGON, "sysdba");
		}
		props.setProperty(OracleConnection.CONNECTION_PROPERTY_THIN_VSESSION_PROGRAM, "oracdc");
		System.setProperty(OracleConnection.CONNECTION_PROPERTY_WALLET_LOCATION, wallet);
		if (auxDbUrl == null || auxWallet == null) {
			auxDbUrl = dbUrl;
			auxWallet = wallet;
		}
		LOGGER.info("Initializing connection to {} database {}...", dbType, dbUrl);
		final OracleDataSource ods = new OracleDataSource();
		ods.setConnectionProperties(props);
		ods.setURL(dbUrl);
		connection4LogMiner = ods.getConnection();
		connection4LogMiner.setAutoCommit(false);

		if (init4Standby) {
			standby = true;
		} else {
			distributed = true;
		}
		LOGGER.info("Connection to {} database {} successfully established.",
				dbType, dbUrl);
	}

	public Connection getLogMinerConnection() throws SQLException {
		return getLogMinerConnection(false);
	}

	public Connection getLogMinerConnection(final boolean trace) throws SQLException {
		final Connection logMinerConnection;
		if (standby || distributed) {
			try {
				((OracleConnection)connection4LogMiner).getDefaultTimeZone(); 
			} catch (SQLException sqle) {
				// Connection is not alive...
				// Need to reinitilize!
				LOGGER.warn("Connection to {} is broken. Trying to reinitialize..",
						standby ? "standby" : "target mining");
				//TODO
				//TODO - better error handling required here!!!
				//TODO
				initConnection4LogMiner(standby, auxDbUrl, auxWallet);
			}
			logMinerConnection = connection4LogMiner;
		} else {
			logMinerConnection = getConnection();
			logMinerConnection.setClientInfo("OCSID.CLIENTID","LogMiner Read-only");
		}
		if (trace) {
			try {
				final OracleStatement alterSession = (OracleStatement) logMinerConnection.createStatement();
				alterSession.execute("alter session set max_dump_file_size=unlimited");
				alterSession.execute("alter session set tracefile_identifier='oracdc'");
				alterSession.execute("alter session set events '10046 trace name context forever, level 8'");
			} catch (SQLException sqle) {
				LOGGER.error("Unble to set trace parameters (max_dump_file_size, tracefile_identifier, and event 10046 level 8)!");
				LOGGER.error("To fix please run:");
				LOGGER.error("\tgrant alter session to {};",
						((OracleConnection)logMinerConnection).getUserName());
			}
		}
		return logMinerConnection;
	}

	public Connection getConnection() throws SQLException {
		try {
			Connection connection = pds.getConnection();
			connection.setClientInfo("OCSID.MODULE","oracdc");
			connection.setClientInfo("OCSID.CLIENTID","Generic R/W");
			connection.setAutoCommit(false);
			return connection;
		} catch(SQLException sqle) {
			if (sqle.getCause() instanceof UniversalConnectionPoolException) {
				UniversalConnectionPoolException ucpe = (UniversalConnectionPoolException) sqle.getCause();
				// Try to handle UCP-45003 and UCP-45386
				// Ref.: https://docs.oracle.com/en/database/oracle/oracle-database/21/jjucp/error-codes-reference.html
				if (ucpe.getErrorCode() == 45003 || ucpe.getErrorCode() == 45386) {
					LOGGER.error("Trying to handle UCP-{} with error message:\n{}",
							ucpe.getErrorCode(), ucpe.getMessage());
					final String newPoolName = poolName + "-" + (version++);
					LOGGER.error("Renaming pool '{}' to '{}'",
							pds.getConnectionPoolName(), newPoolName);
					pds.setConnectionPoolName(newPoolName);
					return getConnection();
				} else {
					throw sqle;
				}
			} else {
				throw sqle;
			}
		}
	}

	public static Connection getConnection(OraCdcSourceConnectorConfig config) throws SQLException {
		final Properties props = new Properties();
		if (StringUtils.isNotBlank(config.getString(ParamConstants.CONNECTION_WALLET_PARAM))) {
			System.setProperty(OracleConnection.CONNECTION_PROPERTY_WALLET_LOCATION,
					config.getString(ParamConstants.CONNECTION_WALLET_PARAM));
		} else {
			props.put(OracleConnection.CONNECTION_PROPERTY_USER_NAME,
					config.getString(ParamConstants.CONNECTION_USER_PARAM));
			props.put(OracleConnection.CONNECTION_PROPERTY_PASSWORD,
					config.getPassword(ParamConstants.CONNECTION_PASSWORD_PARAM).value());
		}
		final Connection connection = DriverManager.getConnection(config.getString(ParamConstants.CONNECTION_URL_PARAM), props);
		return connection;
	}

	public void destroy() throws SQLException {
		try {
			UniversalConnectionPoolManager mgr = UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager();
			mgr.destroyConnectionPool(poolName);
			if (standby || distributed) {
				connection4LogMiner.close();
			}
		} catch (UniversalConnectionPoolException ucpe) {
			throw new SQLException(ucpe);
		}
	}

}
