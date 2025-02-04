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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oracle.jdbc.OracleConnection;
import solutions.a2.cdc.oracle.utils.ExceptionUtils;
import solutions.a2.cdc.oracle.utils.Version;

/**
 * 
 * @author <a href="mailto:averemee@a2.solutions">Aleksei Veremeev</a>
 *
 */
public class OraCdcLogMinerConnector extends SourceConnector {

	private static final Logger LOGGER = LoggerFactory.getLogger(OraCdcLogMinerConnector.class);
	private static final String DB_PARAM_ERROR_GENERIC = "Database connection parameters are not properly set!";
	private static final String TMP_PARAM_ERROR_GENERIC = "Temp directory is not properly set!";
	private static final String DB_PARAM_MUST_SET_WHEN = "Parameter value '{}' must be set when parameter value '{}' is set!";
	private static final String DB_PARAM_MUST_SET_WHEN_TRUE = "Parameter '{}' must be set when '{}' set to true!";
	private static final String VALUE_SET_TO = "Value of parameter '{}' is set to '{}'";

	// Generated using 	https://patorjk.com/software/taag/#p=display&f=Ogre&t=A2%20oracdc
	private static final String LOGO =
		"\n" +
		"   _   ____                            _      \n" +
		"  /_\\ |___ \\    ___  _ __ __ _  ___ __| | ___ \n" +
		" //_\\\\  __) |  / _ \\| '__/ _` |/ __/ _` |/ __|\n" +
		"/  _  \\/ __/  | (_) | | | (_| | (_| (_| | (__ \n" +
		"\\_/ \\_/_____|  \\___/|_|  \\__,_|\\___\\__,_|\\___|\n\n";

	private Map<String, String> connectorProperties;
	private OraCdcSourceConnectorConfig config;

	@Override
	public String version() {
		return Version.getVersion();
	}

	@Override
	public void start(Map<String, String> props) {
		final String connectorName = props.get("name");
		LOGGER.info(LOGO);
		LOGGER.info("Starting oracdc '{}' logminer source connector", connectorName);
		try {
			config = new OraCdcSourceConnectorConfig(props);
			connectorProperties = new HashMap<>();
			connectorProperties.putAll(config.originalsStrings());
			// Copy rest of params...
			config.values().forEach((k, v) -> {
				if (!connectorProperties.containsKey(k) && v != null) {
					if (StringUtils.equals(k, ParamConstants.TABLE_EXCLUDE_PARAM) ||
							StringUtils.equals(k, ParamConstants.TABLE_INCLUDE_PARAM) ||
							StringUtils.equals(k, ParamConstants.CONNECTION_PASSWORD_PARAM) ||
							StringUtils.equals(k, ParamConstants.INTERNAL_RAC_URLS_PARAM)) {
						connectorProperties.put(k, "");
					} else if (v instanceof Boolean) {
						connectorProperties.put(k, ((Boolean) v).toString());
					} else if (v instanceof Short) {
						connectorProperties.put(k, ((Short) v).toString());
					} else if (v instanceof Integer) {
						connectorProperties.put(k, ((Integer) v).toString());
					} else if (v instanceof Long) {
						connectorProperties.put(k, ((Long) v).toString());
					} else {
						connectorProperties.put(k, (String) v);
					}
				}
			});
		} catch (ConfigException ce) {
			throw new ConnectException("Couldn't start oracdc due to coniguration error", ce);
		}

		if (StringUtils.isBlank(config.getString(ParamConstants.CONNECTION_URL_PARAM))) {
			LOGGER.error("Parameter '{}' must be set for running connector!",
					ParamConstants.CONNECTION_URL_PARAM);
					throw new ConnectException(DB_PARAM_ERROR_GENERIC);
		}

		// V1.1.0 - a2.jdbc.url is mandatory parameter! No more separate a2.tns.admin and a2.tns.alias!!!
		checkDeprecatedTnsParameters(props,
				ParamConstants.CONNECTION_TNS_ADMIN_PARAM,
				ParamConstants.CONNECTION_TNS_ALIAS_PARAM,
				ParamConstants.CONNECTION_URL_PARAM);
		checkDeprecatedTnsParameters(props,
				ParamConstants.STANDBY_TNS_ADMIN_PARAM,
				ParamConstants.STANDBY_TNS_ALIAS_PARAM,
				ParamConstants.STANDBY_URL_PARAM);
		checkDeprecatedTnsParameters(props,
				ParamConstants.DISTRIBUTED_TNS_ADMIN_PARAM,
				ParamConstants.DISTRIBUTED_TNS_ALIAS_PARAM,
				ParamConstants.DISTRIBUTED_URL_PARAM);

		if (StringUtils.isBlank(config.getString(ParamConstants.CONNECTION_WALLET_PARAM))) {
			if (StringUtils.isBlank(config.getString(ParamConstants.CONNECTION_USER_PARAM))) {
				LOGGER.error(DB_PARAM_MUST_SET_WHEN,
						ParamConstants.CONNECTION_USER_PARAM,
						ParamConstants.CONNECTION_URL_PARAM);
				throw new ConnectException(DB_PARAM_ERROR_GENERIC);
			}
			if (StringUtils.isBlank(
					config.getPassword(ParamConstants.CONNECTION_PASSWORD_PARAM).value())) {
				LOGGER.error(DB_PARAM_MUST_SET_WHEN,
						ParamConstants.CONNECTION_PASSWORD_PARAM,
						ParamConstants.CONNECTION_URL_PARAM);
				throw new ConnectException(DB_PARAM_ERROR_GENERIC);
			}
			LOGGER.info("Connection to RDBMS will be performed using Oracle username '{}'",
					config.getString(ParamConstants.CONNECTION_USER_PARAM));
		} else {
			LOGGER.info("Connection to RDBMS will be performed using Oracle Wallet '{}'",
					config.getString(ParamConstants.CONNECTION_WALLET_PARAM));
		}




		if (config.getBoolean(ParamConstants.MAKE_STANDBY_ACTIVE_PARAM)) {
			if (StringUtils.isBlank(config.getString(ParamConstants.STANDBY_WALLET_PARAM))) {
				LOGGER.error(DB_PARAM_MUST_SET_WHEN_TRUE,
						ParamConstants.STANDBY_WALLET_PARAM,
						ParamConstants.MAKE_STANDBY_ACTIVE_PARAM);
				throw new ConnectException(DB_PARAM_ERROR_GENERIC);
			}
			if (config.getBoolean(ParamConstants.MAKE_DISTRIBUTED_ACTIVE_PARAM)) {
				LOGGER.warn("When the '{}' parameter is set to true, the '{}' parameter must be set to false!",
						ParamConstants.MAKE_STANDBY_ACTIVE_PARAM,
						ParamConstants.MAKE_DISTRIBUTED_ACTIVE_PARAM);
			}
		}
		if (config.getBoolean(ParamConstants.MAKE_DISTRIBUTED_ACTIVE_PARAM)) {
			if (StringUtils.isBlank(config.getString(ParamConstants.DISTRIBUTED_WALLET_PARAM))) {
				LOGGER.error(DB_PARAM_MUST_SET_WHEN_TRUE,
						ParamConstants.DISTRIBUTED_WALLET_PARAM,
						ParamConstants.MAKE_DISTRIBUTED_ACTIVE_PARAM);
				throw new ConnectException(DB_PARAM_ERROR_GENERIC);
			}
		}
		if (StringUtils.isBlank(config.getString(ParamConstants.TEMP_DIR_PARAM))) {
			connectorProperties.put(ParamConstants.TEMP_DIR_PARAM, System.getProperty("java.io.tmpdir"));
			LOGGER.info(VALUE_SET_TO,
					ParamConstants.TEMP_DIR_PARAM,
					System.getProperty("java.io.tmpdir"));
		}
		if (Files.isDirectory(Paths.get(config.getString(ParamConstants.TEMP_DIR_PARAM)))) {
			if (!Files.isWritable(Paths.get(config.getString(ParamConstants.TEMP_DIR_PARAM)))) {
				LOGGER.error(
						"Parameter '{}' points to non-writable directory '{}'.",
						ParamConstants.TEMP_DIR_PARAM,
						config.getString(ParamConstants.TEMP_DIR_PARAM));
				throw new ConnectException(TMP_PARAM_ERROR_GENERIC);
			}
		} else {
			LOGGER.error(
					"Parameter {} set to non-existent or invalid directory {}.",
					ParamConstants.TEMP_DIR_PARAM,
					config.getString(ParamConstants.TEMP_DIR_PARAM));
			throw new ConnectException(TMP_PARAM_ERROR_GENERIC);
		}

		if (StringUtils.isBlank(config.getString(ParamConstants.PERSISTENT_STATE_FILE_PARAM))) {
			connectorProperties.put(
					ParamConstants.PERSISTENT_STATE_FILE_PARAM,
					System.getProperty("java.io.tmpdir") +  
						(StringUtils.endsWith(
								System.getProperty("java.io.tmpdir"), File.separator) ? "" : File.separator) +
						"oracdc.state");
			LOGGER.info(VALUE_SET_TO,
					ParamConstants.PERSISTENT_STATE_FILE_PARAM,
					connectorProperties.get(ParamConstants.PERSISTENT_STATE_FILE_PARAM));
		}

		if (config.getLong(ParamConstants.LGMNR_START_SCN_PARAM) < 1) {
			connectorProperties.remove(ParamConstants.LGMNR_START_SCN_PARAM);
		}

		if (config.getList(ParamConstants.TABLE_EXCLUDE_PARAM).size() < 1) {
			connectorProperties.remove(ParamConstants.TABLE_EXCLUDE_PARAM);
		}
		if (config.getList(ParamConstants.TABLE_INCLUDE_PARAM).size() < 1) {
			connectorProperties.remove(ParamConstants.TABLE_INCLUDE_PARAM);
		}

		if (config.getLong(ParamConstants.REDO_FILES_SIZE_PARAM) > 0) {
			LOGGER.info("Redo size threshold will be used instead of count of redo files.");
			connectorProperties.remove(ParamConstants.REDO_FILES_COUNT_PARAM);
		} else {
			LOGGER.info("Count of redo files will be used instead of size threshold.");
			connectorProperties.remove(ParamConstants.REDO_FILES_SIZE_PARAM);
			if (config.getShort(ParamConstants.REDO_FILES_COUNT_PARAM) < 1) {
				connectorProperties.put(ParamConstants.REDO_FILES_COUNT_PARAM, "1");
			}
		}

		if (config.getBoolean(ParamConstants.MAKE_DISTRIBUTED_ACTIVE_PARAM)) {
			// When this set we need explicitly value of  a2.archived.log.catalog parameter
			if (!OraCdcDistributedV$ArchivedLogImpl.class.getCanonicalName()
					.equals(config.getString(ParamConstants.ARCHIVED_LOG_CAT_PARAM))) {
				LOGGER.warn("When {} set to true value of {} must be {}.", 
						ParamConstants.MAKE_DISTRIBUTED_ACTIVE_PARAM,
						ParamConstants.ARCHIVED_LOG_CAT_PARAM,
						OraCdcDistributedV$ArchivedLogImpl.class.getCanonicalName());
				LOGGER.warn("Setting {} value to {}.",
						ParamConstants.ARCHIVED_LOG_CAT_PARAM,
						OraCdcDistributedV$ArchivedLogImpl.class.getCanonicalName());
			}
			connectorProperties.put(ParamConstants.ARCHIVED_LOG_CAT_PARAM, 
					OraCdcDistributedV$ArchivedLogImpl.class.getCanonicalName());
		}
	}

	@Override
	public void stop() {
		LOGGER.info("Stopping oracdc logminer source connector");
	}

	@Override
	public Class<? extends Task> taskClass() {
		return OraCdcLogMinerTask.class;
	}

	@Override
	public List<Map<String, String>> taskConfigs(int maxTasks) {
		final List<Map<String, String>> configs = new ArrayList<>();
		List<String> instances = null;
		List<String> urls = null;
		boolean isRac = false;
		if (config.getBoolean(ParamConstants.USE_RAC_PARAM)) {
			try (OracleConnection connection = (OracleConnection) OraConnectionObjects.getConnection(config)) {
				instances = OraRdbmsInfo.getInstances(connection);
				if (instances.size() > 0) {
					if (instances.size() > maxTasks) {
						LOGGER.error("Number of Oracle RAC instances for connection '{}'\n\tis {}, but Kafka Connect 'tasks.max' parameter is set to {}!",
								config.getString(ParamConstants.CONNECTION_URL_PARAM), instances.size(), maxTasks);
						LOGGER.error("Please set value of 'tasks.max' parameter to {} and restart connector!",
								instances.size());
						throw new ConnectException("Please increase value of 'tasks.max' parameter!");
					}
					LOGGER.info("'{}' instances of Oracle RAC found.", instances.size());
					isRac = true;
					urls = OraRdbmsInfo.generateRacJdbcUrls(
							(String )connection.getProperties().get(OracleConnection.CONNECTION_PROPERTY_DATABASE),
							instances);
				} else {
					LOGGER.warn("Parameter '{}' is set to 'true', but no Oracle RAC is detected!",
							ParamConstants.USE_RAC_PARAM);
					LOGGER.warn("Connector continues operations with parameter '{}'='false'",
							ParamConstants.USE_RAC_PARAM);
					connectorProperties.put(ParamConstants.USE_RAC_PARAM, Boolean.FALSE.toString());
				}
			} catch (SQLException sqle) {
				LOGGER.error(ExceptionUtils.getExceptionStackTrace(sqle));
				throw new ConnectException(sqle);
			}
		}
		if (isRac) {
			// We need to replace JDBC URL...
			connectorProperties.put(ParamConstants.INTERNAL_RAC_URLS_PARAM, String.join(",", urls));
			for (int i = 0; i < instances.size(); i++) {
				configs.add(connectorProperties);
				LOGGER.info("Done with configuration of task for Oracle RAC instance '{}'", instances.get(i));
			}
		} else {
			configs.add(connectorProperties);
		}
		return configs;
	}

	@Override
	public ConfigDef config() {
		return OraCdcSourceConnectorConfig.config();
	}

	private void checkDeprecatedTnsParameters(final Map<String, String> props,
			final String tnsAdminParam, final String tnsAliasParam, final String jdbcUrlParam) {
		if (props.containsKey(tnsAdminParam) || props.containsKey(tnsAliasParam)) {
			LOGGER.error("Parameters '{}' and '{}' are deprecated!!!",
					tnsAdminParam, tnsAliasParam);
			LOGGER.error("To connect using TNS alias please set '{}' with JDBC URL format below:", 
					jdbcUrlParam);
			LOGGER.error("\tjdbc:oracle:thin:@<alias_name>?TNS_ADMIN=<directory_with_tnsnames_sqlnet>");
			LOGGER.error("For example:");
			LOGGER.error("\tjdbc:oracle:thin:@prod_db?TNS_ADMIN=/u01/app/oracle/product/21.3.0/dbhome_1/network/admin/");
			LOGGER.error("For more information on JDBC URL format please see Oracle® Database JDBC Java API Reference, Release 21c -");
			LOGGER.error("\thttps://docs.oracle.com/en/database/oracle/oracle-database/21/jajdb/");
			throw new ConnectException(DB_PARAM_ERROR_GENERIC);
		}
	}

}
