// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.Analyzer;
import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.analysis.SlotId;
import com.cloudera.impala.analysis.TupleDescriptor;
import com.cloudera.impala.analysis.TupleId;
import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.PrintUtils;
import com.cloudera.impala.common.TreeNode;
import com.cloudera.impala.thrift.TExplainLevel;
import com.cloudera.impala.thrift.TPlan;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TQueryOptions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Each PlanNode represents a single relational operator
 * and encapsulates the information needed by the planner to
 * make optimization decisions.
 *
 * finalize(): Computes internal state, such as keys for scan nodes; gets called once on
 * the root of the plan tree before the call to toThrift(). Also finalizes the set
 * of conjuncts, such that each remaining one requires all of its referenced slots to
 * be materialized (ie, can be evaluated by calling GetValue(), rather than being
 * implicitly evaluated as part of a scan key).
 *
 * conjuncts: Each node has a list of conjuncts that can be executed in the context of
 * this node, ie, they only reference tuples materialized by this node or one of
 * its children (= are bound by tupleIds).
 */
abstract public class PlanNode extends TreeNode<PlanNode> {
  private final static Logger LOG = LoggerFactory.getLogger(PlanNode.class);

  // TODO: Retrieve from the query options instead of using a default.
  protected final static int DEFAULT_BATCH_SIZE = 1024;

  // String used for this node in getExplainString().
  protected String displayName;

  protected final PlanNodeId id;  // unique w/in plan tree; assigned by planner
  protected long limit; // max. # of rows to be returned; 0: no limit

  // ids materialized by the tree rooted at this node
  protected ArrayList<TupleId> tupleIds;

  // Composition of rows produced by this node. Possibly a superset of tupleIds
  // (the same RowBatch passes through multiple nodes; for instances, join nodes
  // form a left-deep chain and pass RowBatches on to their left children).
  // rowTupleIds[0] is the first tuple in the row, rowTupleIds[1] the second, etc.
  // Set in PlanFragment.finalize()
  protected ArrayList<TupleId> rowTupleIds = Lists.newArrayList();

  // A set of nullable TupleId produced by this node. It is a subset of tupleIds.
  // A tuple is nullable within a particular plan tree if it's the "nullable" side of
  // an outer join, which has nothing to do with the schema.
  protected Set<TupleId> nullableTupleIds = Sets.newHashSet();

  protected List<Expr> conjuncts = Lists.newArrayList();

  // Fragment that this PlanNode is executed in. Valid only after this PlanNode has been
  // assigned to a fragment. Set and maintained by enclosing PlanFragment.
  protected PlanFragment fragment;

  // estimate of the output cardinality of this node; set in computeStats();
  // invalid: -1
  protected long cardinality;

  // number of nodes on which the plan tree rooted at this node would execute;
  // set in computeStats(); invalid: -1
  protected int numNodes;

  // sum of tupleIds' avgSerializedSizes; set in computeStats()
  protected float avgRowSize;

  //  Node should compact data.
  protected boolean compactData;

  // estimated per-host memory requirement for this node;
  // set in computeCosts(); invalid: -1
  protected long perHostMemCost = -1;

  protected PlanNode(PlanNodeId id, ArrayList<TupleId> tupleIds, String displayName) {
    this.id = id;
    this.limit = -1;
    // make a copy, just to be on the safe side
    this.tupleIds = Lists.newArrayList(tupleIds);
    this.cardinality = -1;
    this.numNodes = -1;
    this.displayName = displayName;
  }

  protected PlanNode(PlanNodeId id, String displayName) {
    this.id = id;
    this.limit = -1;
    this.tupleIds = Lists.newArrayList();
    this.cardinality = -1;
    this.numNodes = -1;
    this.displayName = displayName;
  }

  /**
   * Copy c'tor. Also passes in new id.
   */
  protected PlanNode(PlanNodeId id, PlanNode node, String displayName) {
    this.id = id;
    this.limit = node.limit;
    this.tupleIds = Lists.newArrayList(node.tupleIds);
    this.rowTupleIds = Lists.newArrayList(node.rowTupleIds);
    this.nullableTupleIds = Sets.newHashSet(node.nullableTupleIds);
    this.conjuncts = Expr.cloneList(node.conjuncts, null);
    this.cardinality = -1;
    this.numNodes = -1;
    this.compactData = node.compactData;
    this.displayName = displayName;
  }

  public PlanNodeId getId() { return id; }
  public long getLimit() { return limit; }
  public boolean hasLimit() { return limit > -1; }
  public long getPerHostMemCost() { return perHostMemCost; }
  public long getCardinality() { return cardinality; }
  public int getNumNodes() { return numNodes; }
  public float getAvgRowSize() { return avgRowSize; }
  public void setFragment(PlanFragment fragment) { this.fragment = fragment; }
  public PlanFragment getFragment() { return fragment; }

  /** Set the value of compactData in all children. */
  public void setCompactData(boolean on) {
    this.compactData = on;
    for (PlanNode child: this.getChildren()) {
      child.setCompactData(on);
    }
  }

  /**
   * Set the limit to the given limit only if the limit hasn't been set, or the new limit
   * is lower.
   * @param limit
   */
  public void setLimit(long limit) {
    if (this.limit == -1 || (limit != -1 && this.limit > limit)) {
      this.limit = limit;
    }
  }

  public void unsetLimit() { limit = -1; }

  public ArrayList<TupleId> getTupleIds() {
    Preconditions.checkState(tupleIds != null);
    return tupleIds;
  }

  public ArrayList<TupleId> getRowTupleIds() {
    Preconditions.checkState(rowTupleIds != null);
    return rowTupleIds;
  }

  public Set<TupleId> getNullableTupleIds() {
    Preconditions.checkState(nullableTupleIds != null);
    return nullableTupleIds;
  }

  public List<Expr> getConjuncts() { return conjuncts; }

  public void addConjuncts(List<Expr> conjuncts) {
    if (conjuncts == null) {
      return;
    }
    this.conjuncts.addAll(conjuncts);
  }

  public void transferConjuncts(PlanNode recipient) {
    recipient.conjuncts.addAll(conjuncts);
    conjuncts.clear();
  }

  public String getExplainString() {
    return getExplainString("", "", TExplainLevel.NORMAL);
  }

  protected void setDisplayName(String s) { displayName = s; }

  /**
   * Generate the explain plan tree. The plan will be in the form of:
   *
   * root
   * |
   * |----child 2
   * |      limit:1
   * |
   * |----child 3
   * |      limit:2
   * |
   * child 1
   *
   * The root node header line will be prefixed by rootPrefix and the remaining plan
   * output will be prefixed by prefix.
   */
  protected final String getExplainString(String rootPrefix, String prefix,
      TExplainLevel detailLevel) {
    StringBuilder expBuilder = new StringBuilder();
    String detailPrefix = prefix;
    // Do not traverse into the children of an Exchange node to avoid crossing
    // fragment boundaries.
    if (children != null && children.size() > 0 && !(this instanceof ExchangeNode)) {
      detailPrefix += "|  ";
    } else {
      detailPrefix += "   ";
    }

    // Print the current node
    // The plan node header line will be prefixed by rootPrefix and the remaining details
    // will be prefixed by detailPrefix.
    expBuilder.append(rootPrefix + id.asInt() + ":" + displayName + "\n");
    expBuilder.append(getNodeExplainString(detailPrefix, detailLevel));
    if (limit != -1) expBuilder.append(detailPrefix + "limit: " + limit + "\n");
    expBuilder.append(getOffsetExplainString(detailPrefix));

    // Output cardinality, cost estimates and tuple Ids only when explain plan level
    // is set to verbose
    if (detailLevel.equals(TExplainLevel.VERBOSE)) {
      // Print estimated output cardinality and memory cost.
      expBuilder.append(PrintUtils.printCardinality(detailPrefix, cardinality) + "\n");
      expBuilder.append(PrintUtils.printMemCost(detailPrefix, perHostMemCost) + "\n");

      // Print tuple ids.
      expBuilder.append(detailPrefix + "tuple ids: ");
      for (TupleId tupleId: tupleIds) {
        String nullIndicator = nullableTupleIds.contains(tupleId) ? "N" : "";
        expBuilder.append(tupleId.asInt() + nullIndicator + " ");
      }
      expBuilder.append("\n");
    }

    // Print the children. Do not traverse into the children of an Exchange node to
    // avoid crossing fragment boundaries.
    if (children != null && children.size() > 0 && !(this instanceof ExchangeNode)) {
      expBuilder.append(detailPrefix + "\n");
      String childHeadlinePrefix = prefix + "|----";
      String childDetailPrefix = prefix + "|    ";
      for(int i = 1; i < children.size(); ++i) {
        expBuilder.append(
            children.get(i).getExplainString(childHeadlinePrefix, childDetailPrefix,
                detailLevel));
        expBuilder.append(childDetailPrefix + "\n");
      }
      expBuilder.append(children.get(0).getExplainString(prefix, prefix, detailLevel));
    }
    return expBuilder.toString();
  }

  /**
   * Return the node-specific details.
   * Subclass should override this function.
   * Each line should be prefix by detailPrefix.
   */
  protected String getNodeExplainString(String prefix, TExplainLevel detailLevel) {
    return "";
  }

  /**
   * Return the offset details, if applicable. This is available separately from
   * 'getNodeExplainString' because we want to output 'limit: ...' (which can be printed
   * from PlanNode) before 'offset: ...', which is only printed from SortNodes right
   * now.
   */
  protected String getOffsetExplainString(String prefix) {
    return "";
  }

  // Convert this plan node, including all children, to its Thrift representation.
  public TPlan treeToThrift() {
    TPlan result = new TPlan();
    treeToThriftHelper(result);
    return result;
  }

  // Append a flattened version of this plan node, including all children, to 'container'.
  private void treeToThriftHelper(TPlan container) {
    TPlanNode msg = new TPlanNode();
    msg.node_id = id.asInt();
    msg.limit = limit;
    for (TupleId tid: rowTupleIds) {
      msg.addToRow_tuples(tid.asInt());
      msg.addToNullable_tuples(nullableTupleIds.contains(tid));
    }
    for (Expr e: conjuncts) {
      msg.addToConjuncts(e.treeToThrift());
    }
    msg.compact_data = compactData;
    toThrift(msg);
    container.addToNodes(msg);
    // For the purpose of the BE consider ExchangeNodes to have no children.
    if (this instanceof ExchangeNode) {
      msg.num_children = 0;
      return;
    } else {
      msg.num_children = children.size();
      for (PlanNode child: children) {
        child.treeToThriftHelper(container);
      }
    }
  }

  /**
   * Computes internal state, including planner-relevant statistics.
   * Call this once on the root of the plan tree before calling toThrift().
   * Subclasses need to override this.
   */
  public void finalize(Analyzer analyzer) throws InternalException,
      AuthorizationException {
    for (PlanNode child: children) {
      child.finalize(analyzer);
    }
    computeStats(analyzer);
  }

  /**
   * Computes planner statistics: avgRowSize, numNodes, cardinality.
   * Subclasses need to override this.
   * Assumes that it has already been called on all children.
   * This is broken out of finalize() so that it can be called separately
   * from finalize() (to facilitate inserting additional nodes during plan
   * partitioning w/o the need to call finalize() recursively on the whole tree again).
   */
  protected void computeStats(Analyzer analyzer) {
    avgRowSize = 0.0F;
    for (TupleId tid: tupleIds) {
      TupleDescriptor desc = analyzer.getTupleDesc(tid);
      avgRowSize += desc.getAvgSerializedSize();
    }
    if (!children.isEmpty()) {
      numNodes = getChild(0).numNodes;
    }
  }

  /**
   * Compute the product of the selectivies of all conjuncts.
   */
  protected double computeSelectivity() {
    double prod = 1.0;
    for (Expr e: conjuncts) {
      if (e.getSelectivity() < 0) continue;
      prod *= e.getSelectivity();
    }
    return prod;
  }


  /**
   * Appends ids of slots that need to be materialized for this tree of nodes.
   * By default, only slots referenced by conjuncts need to be materialized
   * (the rationale being that only conjuncts need to be evaluated explicitly;
   * exprs that are turned into scan predicates, etc., are evaluated implicitly).
   */
  public void getMaterializedIds(Analyzer analyzer, List<SlotId> ids) {
    for (PlanNode childNode: children) {
      childNode.getMaterializedIds(analyzer, ids);
    }
    Expr.getIds(getConjuncts(), null, ids);
  }

  // Convert this plan node into msg (excluding children), which requires setting
  // the node type and the node-specific field.
  protected abstract void toThrift(TPlanNode msg);

  protected String debugString() {
    // not using Objects.toStrHelper because
    // PlanNode.debugString() is embedded by debug strings of the subclasses
    StringBuilder output = new StringBuilder();
    output.append("preds=" + Expr.debugString(conjuncts));
    output.append(" limit=" + Long.toString(limit));
    return output.toString();
  }

  protected String getExplainString(List<? extends Expr> exprs) {
    if (exprs == null) {
      return "";
    }
    StringBuilder output = new StringBuilder();
    for (int i = 0; i < exprs.size(); ++i) {
      if (i > 0) {
        output.append(", ");
      }
      output.append(exprs.get(i).toSql());
    }
    return output.toString();
  }

  /**
   * Returns true if stats-related variables are valid.
   */
  protected boolean hasValidStats() {
    return (numNodes == -1 || numNodes >= 0) && (cardinality == -1 || cardinality >= 0);
  }

  /**
   * Returns true if this plan node can output its first row only after consuming
   * all rows of all its children. This method is used to group plan nodes
   * into pipelined units for resource estimation.
   */
  public boolean isBlockingNode() { return false; }

  /**
   * Estimates the cost of executing this PlanNode. Currently only sets perHostMemCost.
   * May only be called after this PlanNode has been placed in a PlanFragment because
   * the cost computation is dependent on the enclosing fragment's data partition.
   */
  public void computeCosts(TQueryOptions queryOptions) {
    perHostMemCost = 0;
  }
}
