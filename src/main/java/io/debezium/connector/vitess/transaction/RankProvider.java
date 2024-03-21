/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.vitess.transaction;

import java.math.BigInteger;

public interface RankProvider {
    public BigInteger getRank(String transactionId);
}
