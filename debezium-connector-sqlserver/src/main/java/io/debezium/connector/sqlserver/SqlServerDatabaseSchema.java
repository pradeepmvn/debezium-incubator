/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.sqlserver;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.sqlserver.SqlServerConnectorConfig.DecimalHandlingMode;
import io.debezium.relational.HistorizedRelationalDatabaseSchema;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.TableSchemaBuilder;
import io.debezium.relational.ddl.DdlParser;
import io.debezium.relational.history.TableChanges;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.SchemaChangeEvent.SchemaChangeEventType;
import io.debezium.schema.TopicSelector;
import io.debezium.util.SchemaNameAdjuster;

/**
 * Logical representation of SQL Server schema.
 *
 * @author Jiri Pechanec
 *
 */
public class SqlServerDatabaseSchema extends HistorizedRelationalDatabaseSchema {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlServerDatabaseSchema.class);

    private final Set<TableId> capturedTables;

    public SqlServerDatabaseSchema(SqlServerConnectorConfig connectorConfig, SchemaNameAdjuster schemaNameAdjuster, TopicSelector<TableId> topicSelector, SqlServerConnection connection) {
        super(connectorConfig, topicSelector, connectorConfig.getTableFilters().dataCollectionFilter(), null,
                new TableSchemaBuilder(new SqlServerValueConverters(DecimalHandlingMode
                        .parse(connectorConfig.getConfig().getString(SqlServerConnectorConfig.DECIMAL_HANDLING_MODE))
                        .asDecimalMode()), schemaNameAdjuster, SourceInfo.SCHEMA),
                false);
        try {
            this.capturedTables = determineCapturedTables(connectorConfig, connection);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Could not obtain the list of captured tables", e);
        }
    }

    @Override
    public void applySchemaChange(SchemaChangeEvent schemaChange) {
        LOGGER.debug("Applying schema change event {}", schemaChange);

        // just a single table per DDL event for SQL Server
        Table table = schemaChange.getTables().iterator().next();
        buildAndRegisterSchema(table);
        tables().overwriteTable(table);

        TableChanges tableChanges = null;
        if (schemaChange.getType() == SchemaChangeEventType.CREATE && schemaChange.isFromSnapshot()) {
            tableChanges = new TableChanges();
            tableChanges.create(table);
        }

        record(schemaChange, tableChanges);
    }

    public Set<TableId> getCapturedTables() {
        return capturedTables;
    }

    private static Set<TableId> determineCapturedTables(SqlServerConnectorConfig connectorConfig, SqlServerConnection connection) throws SQLException {
        final Set<TableId> allTableIds = connection.readTableNames(connectorConfig.getDatabaseName(), null, null, new String[] {"TABLE"} );

        final Set<TableId> capturedTables = new HashSet<>();

        for (TableId tableId : allTableIds) {
            if (connectorConfig.getTableFilters().dataCollectionFilter().isIncluded(tableId)) {
                capturedTables.add(tableId);
            }
            else {
                LOGGER.trace("Skipping table {} as it's not included in the filter configuration", tableId);
            }
        }

        return capturedTables;
    }

    @Override
    protected DdlParser getDdlParser() {
        return null;
    }
}
