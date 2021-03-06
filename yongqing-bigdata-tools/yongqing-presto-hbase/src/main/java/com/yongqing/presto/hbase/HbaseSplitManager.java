package com.yongqing.presto.hbase;

import com.yongqing.presto.hbase.model.*;
import com.facebook.presto.spi.*;
import com.facebook.presto.spi.connector.ConnectorSplitManager;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;
import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.schedule.NodeSelectionStrategy;
import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import org.apache.hadoop.hbase.mapreduce.TabletSplitMetadata;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class HbaseSplitManager
        implements ConnectorSplitManager
{

    private static final Logger LOG = Logger.get(HbaseSplitManager.class);

    private final String connectorId;
    private final HbaseClient client;

    @Inject
    public HbaseSplitManager(
            HbaseConnectorId connectorId,
            HbaseClient client)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null").toString();
        this.client = requireNonNull(client, "client is null");
    }
    //TODO
    @Override
    public ConnectorSplitSource getSplits(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorTableLayoutHandle layout, SplitSchedulingContext splitSchedulingContext) {
        HbaseTableLayoutHandle layoutHandle = (HbaseTableLayoutHandle) layout;
        HbaseTableHandle tableHandle = layoutHandle.getTable();

        LOG.debug("getSplits layoutHandle : %s", layoutHandle.toString());

        String schemaName = tableHandle.getSchema();
        String tableName = tableHandle.getTable().contains("__")? tableHandle.getTable().replaceFirst("__","."):tableHandle.getTable();
        String rowIdName = tableHandle.getRowId();

        // Get non-row ID column constraints
        List<HbaseColumnConstraint> constraints = getColumnConstraints(rowIdName, layoutHandle.getConstraint());

        // Get the row domain column range
        Optional<Domain> rDom = getRangeDomain(rowIdName, layoutHandle.getConstraint());

        // Call out to our client to retrieve all tablet split metadata using the row ID domain and the secondary index
        List<TabletSplitMetadata> tabletSplits = client.getTabletSplits(session, schemaName, tableName, rDom, constraints); //tableHandle.getSerializerInstance()

        // Pack the tablet split metadata into a connector split
        ImmutableList.Builder<ConnectorSplit> cSplits = ImmutableList.builder();
        for (TabletSplitMetadata splitMetadata : tabletSplits) {
            HbaseSplit split = new HbaseSplit(
                    connectorId,
                    schemaName,
                    tableName,
                    rowIdName,
                    splitMetadata,
                    //TODO
                    NodeSelectionStrategy.NO_PREFERENCE,
                    constraints,
                    tableHandle.getScanAuthorizations());
            cSplits.add(split);
        }

        return new FixedSplitSource(cSplits.build());
    }


    private static Optional<Domain> getRangeDomain(String rowIdName, TupleDomain<ColumnHandle> constraint)
    {
        if (constraint.getColumnDomains().isPresent()) {
            for (TupleDomain.ColumnDomain<ColumnHandle> cd : constraint.getColumnDomains().get()) {
                HbaseColumnHandle col = (HbaseColumnHandle) cd.getColumn();
                if (col.getName().equals(rowIdName)) {
                    return Optional.of(cd.getDomain());
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Gets a list of {@link HbaseColumnConstraint} based on the given constraint ID, excluding the row ID column
     *
     * @param rowIdName Presto column name mapping to the Hbase row ID
     * @param constraint Set of query constraints
     * @return List of all column constraints
     */
    private static List<HbaseColumnConstraint> getColumnConstraints(String rowIdName, TupleDomain<ColumnHandle> constraint)
    {
        ImmutableList.Builder<HbaseColumnConstraint> constraintBuilder = ImmutableList.builder();
        for (TupleDomain.ColumnDomain<ColumnHandle> columnDomain : constraint.getColumnDomains().get()) {
            HbaseColumnHandle columnHandle = (HbaseColumnHandle) columnDomain.getColumn();

            if (!columnHandle.getName().equals(rowIdName)) {
                // Family and qualifier will exist for non-row ID columns
                constraintBuilder.add(new HbaseColumnConstraint(
                        columnHandle.getName(),
                        columnHandle.getFamily().get(),
                        columnHandle.getQualifier().get(),
                        Optional.of(columnDomain.getDomain()),
                        columnHandle.isIndexed()));
            }
        }

        return constraintBuilder.build();
    }
}
