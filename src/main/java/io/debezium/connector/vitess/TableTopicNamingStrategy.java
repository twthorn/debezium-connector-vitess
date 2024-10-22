/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.vitess;

import java.util.Properties;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.relational.TableId;
import io.debezium.schema.AbstractTopicNamingStrategy;
import io.debezium.util.Collect;
import io.debezium.util.Strings;

/**
 * Topic naming strategy where only the table name is added. This is used to avoid including
 * the shard which is now part of the catalog of the table ID and would be included if
 * the DefaultTopicNamingStrategy is being used.
 */
public class TableTopicNamingStrategy extends AbstractTopicNamingStrategy<TableId> {

    private final String overrideDataChangeTopicPrefix;
    private final String overrideSchemaChangeTopic;

    public TableTopicNamingStrategy(Properties props) {
        super(props);
        Configuration config = Configuration.from(props);
        this.overrideDataChangeTopicPrefix = config.getString(VitessConnectorConfig.OVERRIDE_DATA_CHANGE_TOPIC_PREFIX);
        this.overrideSchemaChangeTopic = config.getString(VitessConnectorConfig.OVERRIDE_SCHEMA_CHANGE_TOPIC);
    }

    public static TableTopicNamingStrategy create(CommonConnectorConfig config) {
        return new TableTopicNamingStrategy(config.getConfig().asProperties());
    }

    @Override
    public String dataChangeTopic(TableId id) {
        String topicName;
        if (!Strings.isNullOrBlank(overrideDataChangeTopicPrefix)) {
            topicName = mkString(Collect.arrayListOf(overrideDataChangeTopicPrefix, id.table()), delimiter);
        }
        else {
            topicName = mkString(Collect.arrayListOf(prefix, id.table()), delimiter);
        }
        return topicNames.computeIfAbsent(id, t -> sanitizedTopicName(topicName));
    }

    /**
     * Return the schema change topic. There are two cases:
     * 1. If override schema change topic is specified - use this as the topic name
     * 2. If override schema change topic is not specified - call the super method to get the typical
     * schema change topic name.
     *
     * @return String representing the schema change topic name.
     */
    @Override
    public String schemaChangeTopic() {
        if (!Strings.isNullOrBlank(overrideSchemaChangeTopic)) {
            return overrideSchemaChangeTopic;
        }
        else {
            return super.schemaChangeTopic();
        }
    }
}
