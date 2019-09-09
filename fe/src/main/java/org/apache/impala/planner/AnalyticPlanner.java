// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.impala.analysis.AggregateInfoBase;
import org.apache.impala.analysis.AnalyticExpr;
import org.apache.impala.analysis.AnalyticInfo;
import org.apache.impala.analysis.AnalyticWindow;
import org.apache.impala.analysis.Analyzer;
import org.apache.impala.analysis.BinaryPredicate;
import org.apache.impala.analysis.BoolLiteral;
import org.apache.impala.analysis.CompoundPredicate;
import org.apache.impala.analysis.CompoundPredicate.Operator;
import org.apache.impala.analysis.Expr;
import org.apache.impala.analysis.ExprSubstitutionMap;
import org.apache.impala.analysis.IsNullPredicate;
import org.apache.impala.analysis.OrderByElement;
import org.apache.impala.analysis.SlotDescriptor;
import org.apache.impala.analysis.SlotRef;
import org.apache.impala.analysis.SortInfo;
import org.apache.impala.analysis.TupleDescriptor;
import org.apache.impala.analysis.TupleId;
import org.apache.impala.analysis.TupleIsNullPredicate;
import org.apache.impala.common.ImpalaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;


/**
 * The analytic planner adds plan nodes to an existing plan tree in order to
 * implement the AnalyticInfo of a given query stmt. The resulting plan reflects
 * similarities among analytic exprs with respect to partitioning, ordering and
 * windowing to reduce data exchanges and sorts (the exchanges and sorts are currently
 * not minimal). The generated plan has the following structure:
 * ...
 * (
 *  (
 *    (
 *      analytic node  <-- group of analytic exprs with compatible window
 *    )+               <-- group of analytic exprs with compatible ordering
 *    sort node?
 *  )+                 <-- group of analytic exprs with compatible partitioning
 *  hash exchange?
 * )*                  <-- group of analytic exprs that have different partitioning
 * input plan node
 * ...
 */
public class AnalyticPlanner {
  private final static Logger LOG = LoggerFactory.getLogger(AnalyticPlanner.class);

  private final AnalyticInfo analyticInfo_;
  private final Analyzer analyzer_;
  private final PlannerContext ctx_;

  public AnalyticPlanner(AnalyticInfo analyticInfo, Analyzer analyzer,
      PlannerContext ctx) {
    analyticInfo_ = analyticInfo;
    analyzer_ = analyzer;
    ctx_ = ctx;
  }

  /**
   * Return true if and only if exprs is non-empty and contains non-constant
   * expressions.
   */
  private boolean activeExprs(List<Expr> exprs) {
    if (exprs.isEmpty())  return false;
    for (Expr p: exprs) {
      if (!p.isConstant()) { return true; }
    }
    return false;
  }

  /**
   * Return plan tree that augments 'root' with plan nodes that implement single-node
   * evaluation of the AnalyticExprs in analyticInfo.
   * This plan takes into account a possible hash partition of its input on
   * 'groupingExprs'; if this is non-null, it returns in 'inputPartitionExprs'
   * a subset of the grouping exprs which should be used for the aggregate
   * hash partitioning during the parallelization of 'root'.
   * TODO: when generating sort orders for the sort groups, optimize the ordering
   * of the partition exprs (so that subsequent sort operations see the input sorted
   * on a prefix of their required sort exprs)
   * TODO: when merging sort groups, recognize equivalent exprs
   * (using the equivalence classes) rather than looking for expr equality
   */
  public PlanNode createSingleNodePlan(PlanNode root,
      List<Expr> groupingExprs, List<Expr> inputPartitionExprs) throws ImpalaException {
    List<WindowGroup> windowGroups = collectWindowGroups();
    for (int i = 0; i < windowGroups.size(); ++i) {
      windowGroups.get(i).init(analyzer_, "wg-" + i);
    }
    List<SortGroup> sortGroups = collectSortGroups(windowGroups);
    mergeSortGroups(sortGroups);
    for (SortGroup g: sortGroups) {
      g.init();
    }
    List<PartitionGroup> partitionGroups = collectPartitionGroups(sortGroups);
    mergePartitionGroups(partitionGroups, root.getNumNodes());
    orderGroups(partitionGroups);
    if (groupingExprs != null) {
      Preconditions.checkNotNull(inputPartitionExprs);
      computeInputPartitionExprs(
          partitionGroups, groupingExprs, root.getNumNodes(), inputPartitionExprs);
    }

    for (PartitionGroup partitionGroup: partitionGroups) {
      for (int i = 0; i < partitionGroup.sortGroups.size(); ++i) {
        root = createSortGroupPlan(root, partitionGroup.sortGroups.get(i),
            i == 0 ? partitionGroup.partitionByExprs : null);
      }
    }
    return root;
  }

  /**
   * Coalesce sort groups that have compatible partition-by exprs and
   * have a prefix relationship.
   */
  private void mergeSortGroups(List<SortGroup> sortGroups) {
    boolean hasMerged = false;
    do {
      hasMerged = false;
      for (SortGroup sg1: sortGroups) {
        for (SortGroup sg2: sortGroups) {
          if (sg1 != sg2 && sg1.isPrefixOf(sg2)) {
            sg1.absorb(sg2);
            sortGroups.remove(sg2);
            hasMerged = true;
            break;
          }
        }
        if (hasMerged) break;
      }
    } while (hasMerged);
  }

  /**
   * Coalesce partition groups for which the intersection of their
   * partition exprs has ndv estimate > numNodes, so that the resulting plan
   * still parallelizes across all nodes.
   */
  private void mergePartitionGroups(
      List<PartitionGroup> partitionGroups, int numNodes) {
    boolean hasMerged = false;
    do {
      hasMerged = false;
      for (PartitionGroup pg1: partitionGroups) {
        for (PartitionGroup pg2: partitionGroups) {
          if (pg1 != pg2) {
            long ndv = Expr.getNumDistinctValues(
                Expr.intersect(pg1.partitionByExprs, pg2.partitionByExprs));
            if (ndv == -1 || ndv < 0 || ndv < numNodes) {
              // didn't get a usable value or the number of partitions is too small
              continue;
            }
            pg1.merge(pg2);
            partitionGroups.remove(pg2);
            hasMerged = true;
            break;
          }
        }
        if (hasMerged) break;
      }
    } while (hasMerged);
  }

  /**
   * Determine the partition group that has the maximum intersection in terms
   * of the estimated ndv of the partition exprs with groupingExprs.
   * That partition group is placed at the front of partitionGroups, with its
   * partition exprs reduced to the intersection, and the intersecting groupingExprs
   * are returned in inputPartitionExprs.
   */
  private void computeInputPartitionExprs(List<PartitionGroup> partitionGroups,
      List<Expr> groupingExprs, int numNodes, List<Expr> inputPartitionExprs) {
    inputPartitionExprs.clear();
    Preconditions.checkState(numNodes != -1);
    // find partition group with maximum intersection
    long maxNdv = 0;
    PartitionGroup maxPg = null;
    List<Expr> maxGroupingExprs = null;
    for (PartitionGroup pg: partitionGroups) {
      List<Expr> l1 = new ArrayList<>();
      List<Expr> l2 = new ArrayList<>();
      analyzer_.exprIntersect(pg.partitionByExprs, groupingExprs, l1, l2);
      // TODO: also look at l2 and take the max?
      long ndv = Expr.getNumDistinctValues(l1);
      if (LOG.isTraceEnabled()) {
        LOG.trace(String.format("Partition group: %s, intersection: %s. " +
                "GroupingExprs: %s, intersection: %s. ndv: %d, numNodes: %d, maxNdv: %d.",
            Expr.debugString(pg.partitionByExprs), Expr.debugString(l1),
            Expr.debugString(groupingExprs), Expr.debugString(l2),
            ndv, numNodes, maxNdv));
      }
      if (ndv < 0 || ndv < numNodes || ndv < maxNdv) continue;
      // found a better partition group
      maxPg = pg;
      maxPg.partitionByExprs = l1;
      maxGroupingExprs = l2;
      maxNdv = ndv;
    }

    if (maxNdv > numNodes) {
      Preconditions.checkNotNull(maxPg);
      // we found a partition group that gives us enough parallelism;
      // move it to the front
      partitionGroups.remove(maxPg);
      partitionGroups.add(0, maxPg);
      inputPartitionExprs.addAll(maxGroupingExprs);
      if (LOG.isTraceEnabled()) {
        LOG.trace("Optimized partition exprs: " + Expr.debugString(inputPartitionExprs));
      }
    }
  }

  /**
   * Order partition groups (and the sort groups within them) by increasing
   * totalOutputTupleSize. This minimizes the total volume of data that needs to be
   * repartitioned and sorted.
   * Also move the non-partitioning partition group to the end.
   */
  private void orderGroups(List<PartitionGroup> partitionGroups) {
    // remove the non-partitioning group from partitionGroups
    PartitionGroup nonPartitioning = null;
    for (PartitionGroup pg: partitionGroups) {
      if (!activeExprs(pg.partitionByExprs)) {
        nonPartitioning = pg;
        break;
      }
    }
    if (nonPartitioning != null) partitionGroups.remove(nonPartitioning);

    // order by ascending combined output tuple size
    Collections.sort(partitionGroups,
        new Comparator<PartitionGroup>() {
          @Override
          public int compare(PartitionGroup pg1, PartitionGroup pg2) {
            Preconditions.checkState(pg1.totalOutputTupleSize > 0);
            Preconditions.checkState(pg2.totalOutputTupleSize > 0);
            int diff = pg1.totalOutputTupleSize - pg2.totalOutputTupleSize;
            return (diff < 0 ? -1 : (diff > 0 ? 1 : 0));
          }
        });
    if (nonPartitioning != null) partitionGroups.add(nonPartitioning);

    for (PartitionGroup pg: partitionGroups) {
      pg.orderSortGroups();
    }
  }

  /**
   * Create SortInfo, including sort tuple, to sort entire input row
   * on sortExprs.
   */
  private SortInfo createSortInfo(
      PlanNode input, List<Expr> sortExprs, List<Boolean> isAsc,
      List<Boolean> nullsFirst) {
    List<Expr> inputSlotRefs = new ArrayList<>();
    for (TupleId tid: input.getTupleIds()) {
      TupleDescriptor tupleDesc = analyzer_.getTupleDesc(tid);
      for (SlotDescriptor inputSlotDesc: tupleDesc.getSlots()) {
        if (!inputSlotDesc.isMaterialized()) continue;
        if (inputSlotDesc.getType().isComplexType()) {
          // Project out collection slots since they won't be used anymore and may cause
          // troubles like IMPALA-8718. They won't be used since outputs of the analytic
          // node must be in the select list of the block with the analytic, and we don't
          // allow collection types to be returned from a select block, and also don't
          // support any builtin or UDF functions that take collection types as an
          // argument.
          if (LOG.isTraceEnabled()) {
            LOG.trace("Project out collection slot in sort tuple of analytic: slot={}",
                inputSlotDesc.debugString());
          }
          continue;
        }
        inputSlotRefs.add(new SlotRef(inputSlotDesc));
      }
    }

    // The decision to materialize ordering exprs should be based on exprs that are
    // fully resolved against our input (IMPALA-5270).
    ExprSubstitutionMap inputSmap = input.getOutputSmap();
    List<Expr> resolvedSortExprs =
        Expr.substituteList(sortExprs, inputSmap, analyzer_, true);
    SortInfo sortInfo = new SortInfo(resolvedSortExprs, isAsc, nullsFirst);
    sortInfo.createSortTupleInfo(inputSlotRefs, analyzer_);

    // Lhs exprs to be substituted in ancestor plan nodes could have a rhs that contains
    // TupleIsNullPredicates. TupleIsNullPredicates require specific tuple ids for
    // evaluation. Since this sort materializes a new tuple, it's impossible to evaluate
    // TupleIsNullPredicates referring to this sort's input after this sort,
    // To preserve the information whether an input tuple was null or not this sort node,
    // we materialize those rhs TupleIsNullPredicates, which are then substituted
    // by a SlotRef into the sort's tuple in ancestor nodes (IMPALA-1519).
    if (inputSmap != null) {
      List<TupleIsNullPredicate> tupleIsNullPreds = new ArrayList<>();
      for (Expr rhsExpr: inputSmap.getRhs()) {
        // Ignore substitutions that are irrelevant at this plan node and its ancestors.
        if (!rhsExpr.isBoundByTupleIds(input.getTupleIds())) continue;
        rhsExpr.collect(TupleIsNullPredicate.class, tupleIsNullPreds);
      }
      Expr.removeDuplicates(tupleIsNullPreds);
      sortInfo.addMaterializedExprs(tupleIsNullPreds, analyzer_);
    }
    sortInfo.getSortTupleDescriptor().materializeSlots();
    return sortInfo;
  }

  /**
   * Create plan tree for the entire sort group, including all contained window groups.
   * Marks the SortNode as requiring its input to be partitioned if partitionExprs
   * is not null (partitionExprs represent the data partition of the entire partition
   * group of which this sort group is a part).
   */
  private PlanNode createSortGroupPlan(PlanNode root, SortGroup sortGroup,
      List<Expr> partitionExprs) throws ImpalaException {
    List<Expr> partitionByExprs = sortGroup.partitionByExprs;
    List<OrderByElement> orderByElements = sortGroup.orderByElements;
    ExprSubstitutionMap sortSmap = null;
    TupleId sortTupleId = null;
    TupleDescriptor bufferedTupleDesc = null;
    // map from input to buffered tuple
    ExprSubstitutionMap bufferedSmap = new ExprSubstitutionMap();
    boolean activePartition = activeExprs(partitionByExprs);

    // IMPALA-8069: Ignore something like ORDER BY 0
    boolean isConstSort = true;
    for (OrderByElement elmt : orderByElements) {
      isConstSort = isConstSort && elmt.getExpr().isConstant();
    }
    // sort on partition by (pb) + order by (ob) exprs and create pb/ob predicates
    if (activePartition || !isConstSort) {
      // first sort on partitionExprs (direction doesn't matter)
      List<Expr> sortExprs = Lists.newArrayList(partitionByExprs);
      List<Boolean> isAsc =
          Lists.newArrayList(Collections.nCopies(sortExprs.size(), new Boolean(true)));
      // TODO: utilize a direction and nulls/first last that has benefit
      // for subsequent sort groups
      List<Boolean> nullsFirst =
          Lists.newArrayList(Collections.nCopies(sortExprs.size(), new Boolean(true)));

      // then sort on orderByExprs
      for (OrderByElement orderByElement: sortGroup.orderByElements) {
        // If the expr is in the PARTITION BY and already in 'sortExprs', but also in
        // the ORDER BY, its unnecessary to add it to 'sortExprs' again.
        if (!sortExprs.contains(orderByElement.getExpr())) {
          sortExprs.add(orderByElement.getExpr());
          isAsc.add(orderByElement.isAsc());
          nullsFirst.add(orderByElement.getNullsFirstParam());
        }
      }

      SortInfo sortInfo = createSortInfo(root, sortExprs, isAsc, nullsFirst);
      SortNode sortNode =
          SortNode.createTotalSortNode(ctx_.getNextNodeId(), root, sortInfo, 0);

      // if this sort group does not have partitioning exprs, we want the sort
      // to be executed like a regular distributed sort
      if (activePartition) sortNode.setIsAnalyticSort(true);

      if (partitionExprs != null) {
        // create required input partition
        DataPartition inputPartition = DataPartition.UNPARTITIONED;
        if (activePartition) {
          inputPartition = DataPartition.hashPartitioned(partitionExprs);
        }
        sortNode.setInputPartition(inputPartition);
      }

      root = sortNode;
      root.init(analyzer_);
    }
    if (activePartition || !orderByElements.isEmpty()) {
      sortSmap = root.getOutputSmap();

      // create bufferedTupleDesc and bufferedSmap
      sortTupleId = root.tupleIds_.get(0);
      bufferedTupleDesc =
          analyzer_.getDescTbl().copyTupleDescriptor(sortTupleId, "buffered-tuple");
      if (LOG.isTraceEnabled()) {
        LOG.trace("desctbl: " + analyzer_.getDescTbl().debugString());
      }

      List<SlotDescriptor> inputSlots = analyzer_.getTupleDesc(sortTupleId).getSlots();
      List<SlotDescriptor> bufferedSlots = bufferedTupleDesc.getSlots();
      for (int i = 0; i < inputSlots.size(); ++i) {
        bufferedSmap.put(
            new SlotRef(inputSlots.get(i)), new SlotRef(bufferedSlots.get(i)));
      }
    }

    // create one AnalyticEvalNode per window group
    for (WindowGroup windowGroup: sortGroup.windowGroups) {
      // Create partition-by (pb) and order-by (ob) less-than predicates between the
      // input tuple (the output of the preceding sort) and a buffered tuple that is
      // identical to the input tuple. We need a different tuple descriptor for the
      // buffered tuple because the generated predicates should compare two different
      // tuple instances from the same input stream (i.e., the predicates should be
      // evaluated over a row that is composed of the input and the buffered tuple).

      // we need to remap the pb/ob exprs to a) the sort output, b) our buffer of the
      // sort input
      Expr partitionByEq = null;
      if (activeExprs(windowGroup.partitionByExprs)) {
        partitionByEq = createNullMatchingEquals(
            Expr.substituteList(windowGroup.partitionByExprs, sortSmap, analyzer_, false),
            sortTupleId, bufferedSmap);
        if (LOG.isTraceEnabled()) {
          LOG.trace("partitionByEq: " + partitionByEq.debugString());
        }
      }
      Expr orderByEq = null;
      if (!windowGroup.orderByElements.isEmpty()) {
        orderByEq = createNullMatchingEquals(
            OrderByElement.getOrderByExprs(OrderByElement.substitute(
                windowGroup.orderByElements, sortSmap, analyzer_)),
            sortTupleId, bufferedSmap);
        if (LOG.isTraceEnabled()) {
          LOG.trace("orderByEq: " + orderByEq.debugString());
        }
      }

      root = new AnalyticEvalNode(ctx_.getNextNodeId(), root,
          windowGroup.analyticFnCalls, windowGroup.partitionByExprs,
          windowGroup.orderByElements, windowGroup.window,
          windowGroup.physicalIntermediateTuple, windowGroup.physicalOutputTuple,
          windowGroup.logicalToPhysicalSmap,
          partitionByEq, orderByEq, bufferedTupleDesc);
      root.init(analyzer_);
    }
    return root;
  }

  /**
   * Create a predicate that checks if all exprs are equal or both sides are null.
   */
  private Expr createNullMatchingEquals(List<Expr> exprs, TupleId inputTid,
      ExprSubstitutionMap bufferedSmap) {
    Preconditions.checkState(!exprs.isEmpty());
    Expr result = createNullMatchingEqualsAux(exprs, 0, inputTid, bufferedSmap);
    result.analyzeNoThrow(analyzer_);
    return result;
  }

  /**
   * Create an unanalyzed predicate that checks if elements >= i are equal or
   * both sides are null.
   *
   * The predicate has the form
   * ((lhs[i] is null && rhs[i] is null) || (
   *   lhs[i] is not null && rhs[i] is not null && lhs[i] = rhs[i]))
   * && <createEqualsAux(i + 1)>
   */
  private Expr createNullMatchingEqualsAux(List<Expr> elements, int i,
      TupleId inputTid, ExprSubstitutionMap bufferedSmap) {
    if (i > elements.size() - 1) return new BoolLiteral(true);

    // compare elements[i]
    Expr lhs = elements.get(i);
    Preconditions.checkState(lhs.isBound(inputTid));
    Expr rhs = lhs.substitute(bufferedSmap, analyzer_, false);

    Expr bothNull = new CompoundPredicate(Operator.AND,
        new IsNullPredicate(lhs, false), new IsNullPredicate(rhs, false));
    Expr lhsEqRhsNotNull = new CompoundPredicate(Operator.AND,
        new CompoundPredicate(Operator.AND,
            new IsNullPredicate(lhs, true), new IsNullPredicate(rhs, true)),
        new BinaryPredicate(BinaryPredicate.Operator.EQ, lhs, rhs));
    Expr remainder = createNullMatchingEqualsAux(elements, i + 1, inputTid, bufferedSmap);
    return new CompoundPredicate(CompoundPredicate.Operator.AND,
        new CompoundPredicate(Operator.OR, bothNull, lhsEqRhsNotNull),
        remainder);
  }

  /**
   * Collection of AnalyticExprs that share the same partition-by/order-by/window
   * specification. The AnalyticExprs are stored broken up into their constituent parts.
   */
  private static class WindowGroup {
    public final List<Expr> partitionByExprs;
    public final List<OrderByElement> orderByElements;
    public final AnalyticWindow window; // not null

    // Analytic exprs belonging to this window group and their corresponding logical
    // intermediate and output slots from AnalyticInfo.intermediateTupleDesc_
    // and AnalyticInfo.outputTupleDesc_.
    public final List<AnalyticExpr> analyticExprs = new ArrayList<>();
    // Result of getFnCall() for every analytic expr.
    public final List<Expr> analyticFnCalls = new ArrayList<>();
    public final List<SlotDescriptor> logicalOutputSlots = new ArrayList<>();
    public final List<SlotDescriptor> logicalIntermediateSlots = new ArrayList<>();

    // Physical output and intermediate tuples as well as an smap that maps the
    // corresponding logical output slots to their physical slots in physicalOutputTuple.
    // Set in init().
    public TupleDescriptor physicalOutputTuple;
    public TupleDescriptor physicalIntermediateTuple;
    public final ExprSubstitutionMap logicalToPhysicalSmap = new ExprSubstitutionMap();

    public WindowGroup(AnalyticExpr analyticExpr, SlotDescriptor logicalOutputSlot,
        SlotDescriptor logicalIntermediateSlot) {
      partitionByExprs = analyticExpr.getPartitionExprs();
      orderByElements = analyticExpr.getOrderByElements();
      window = analyticExpr.getWindow();
      analyticExprs.add(analyticExpr);
      analyticFnCalls.add(analyticExpr.getFnCall());
      logicalOutputSlots.add(logicalOutputSlot);
      logicalIntermediateSlots.add(logicalIntermediateSlot);
    }

    /**
     * True if this analytic function must be evaluated in its own WindowGroup.
     */
    private static boolean requiresIndependentEval(AnalyticExpr analyticExpr) {
      return analyticExpr.getFnCall().getFnName().getFunction().equals(
          AnalyticExpr.FIRST_VALUE_REWRITE);
    }

    /**
     * True if the partition exprs and ordering elements and the window of analyticExpr
     * match ours.
     */
    public boolean isCompatible(AnalyticExpr analyticExpr) {
      if (requiresIndependentEval(analyticExprs.get(0)) ||
          requiresIndependentEval(analyticExpr)) {
        return false;
      }

      if (!Expr.equalSets(analyticExpr.getPartitionExprs(), partitionByExprs)) {
        return false;
      }
      if (!analyticExpr.getOrderByElements().equals(orderByElements)) return false;
      if ((window == null) != (analyticExpr.getWindow() == null)) return false;
      if (window == null) return true;
      return analyticExpr.getWindow().equals(window);
    }

    /**
     * Adds the given analytic expr and its logical slots to this window group.
     * Assumes the corresponding analyticExpr is compatible with 'this'.
     */
    public void add(AnalyticExpr analyticExpr, SlotDescriptor logicalOutputSlot,
        SlotDescriptor logicalIntermediateSlot) {
      Preconditions.checkState(isCompatible(analyticExpr));
      analyticExprs.add(analyticExpr);
      analyticFnCalls.add(analyticExpr.getFnCall());
      logicalOutputSlots.add(logicalOutputSlot);
      logicalIntermediateSlots.add(logicalIntermediateSlot);
    }

    /**
     * Creates the physical output and intermediate tuples as well as the logical to
     * physical smap for this window group. Computes the mem layout for the tuple
     * descriptors.
     */
    public void init(Analyzer analyzer, String tupleName) {
      Preconditions.checkState(physicalOutputTuple == null);
      Preconditions.checkState(physicalIntermediateTuple == null);
      Preconditions.checkState(analyticFnCalls.size() == analyticExprs.size());

      // If needed, create the intermediate tuple first to maintain
      // intermediateTupleId < outputTupleId for debugging purposes and consistency with
      // tuple creation for aggregations.
      boolean requiresIntermediateTuple =
          AggregateInfoBase.requiresIntermediateTuple(analyticFnCalls);
      if (requiresIntermediateTuple) {
        physicalIntermediateTuple =
            analyzer.getDescTbl().createTupleDescriptor(tupleName + "intermed");
        physicalOutputTuple =
            analyzer.getDescTbl().createTupleDescriptor(tupleName + "out");
      } else {
        physicalOutputTuple =
            analyzer.getDescTbl().createTupleDescriptor(tupleName + "out");
        physicalIntermediateTuple = physicalOutputTuple;
      }

      Preconditions.checkState(analyticExprs.size() == logicalIntermediateSlots.size());
      Preconditions.checkState(analyticExprs.size() == logicalOutputSlots.size());
      for (int i = 0; i < analyticExprs.size(); ++i) {
        SlotDescriptor logicalOutputSlot = logicalOutputSlots.get(i);
        SlotDescriptor physicalOutputSlot =
            analyzer.copySlotDescriptor(logicalOutputSlot, physicalOutputTuple);
        physicalOutputSlot.setIsMaterialized(true);
        if (requiresIntermediateTuple) {
          SlotDescriptor logicalIntermediateSlot = logicalIntermediateSlots.get(i);
          SlotDescriptor physicalIntermediateSlot = analyzer.copySlotDescriptor(
              logicalIntermediateSlot, physicalIntermediateTuple);
          physicalIntermediateSlot.setIsMaterialized(true);
        }
        logicalToPhysicalSmap.put(
            new SlotRef(logicalOutputSlot), new SlotRef(physicalOutputSlot));
      }
      physicalOutputTuple.computeMemLayout();
      if (requiresIntermediateTuple) physicalIntermediateTuple.computeMemLayout();
    }
  }

  /**
   * Extract a minimal set of WindowGroups from analyticExprs.
   */
  private List<WindowGroup> collectWindowGroups() {
    List<AnalyticExpr> analyticExprs = analyticInfo_.getAnalyticExprs();
    List<WindowGroup> groups = new ArrayList<>();
    for (int i = 0; i < analyticExprs.size(); ++i) {
      AnalyticExpr analyticExpr = (AnalyticExpr) analyticExprs.get(i);
      // Do not generate the plan for non-materialized analytic exprs.
      if (!analyticInfo_.getOutputTupleDesc().getSlots().get(i).isMaterialized()) {
        continue;
      }
      boolean match = false;
      for (WindowGroup group: groups) {
        if (group.isCompatible(analyticExpr)) {
          group.add((AnalyticExpr) analyticInfo_.getAnalyticExprs().get(i),
              analyticInfo_.getOutputTupleDesc().getSlots().get(i),
              analyticInfo_.getIntermediateTupleDesc().getSlots().get(i));
          match = true;
          break;
        }
      }
      if (!match) {
        groups.add(new WindowGroup(
            (AnalyticExpr) analyticInfo_.getAnalyticExprs().get(i),
            analyticInfo_.getOutputTupleDesc().getSlots().get(i),
            analyticInfo_.getIntermediateTupleDesc().getSlots().get(i)));
      }
    }
    return groups;
  }

  /**
   * Collection of WindowGroups that share the same partition-by/order-by
   * specification.
   */
  private static class SortGroup {
    public List<Expr> partitionByExprs;
    public List<OrderByElement> orderByElements;
    public List<WindowGroup> windowGroups = new ArrayList<>();

    // sum of windowGroups.physicalOutputTuple.getByteSize()
    public int totalOutputTupleSize = -1;

    public SortGroup(WindowGroup windowGroup) {
      partitionByExprs = windowGroup.partitionByExprs;
      orderByElements = windowGroup.orderByElements;
      windowGroups.add(windowGroup);
    }

    /**
     * True if the partition and ordering exprs of windowGroup match ours.
     */
    public boolean isCompatible(WindowGroup windowGroup) {
      return Expr.equalSets(windowGroup.partitionByExprs, partitionByExprs)
          && windowGroup.orderByElements.equals(orderByElements);
    }

    public void add(WindowGroup windowGroup) {
      Preconditions.checkState(isCompatible(windowGroup));
      windowGroups.add(windowGroup);
    }

    /**
     * Return true if 'this' and other have compatible partition exprs and
     * our orderByElements are a prefix of other's.
     */
    public boolean isPrefixOf(SortGroup other) {
      if (other.orderByElements.size() > orderByElements.size()) return false;
      if (!Expr.equalSets(partitionByExprs, other.partitionByExprs)) return false;
      for (int i = 0; i < other.orderByElements.size(); ++i) {
        OrderByElement ob = orderByElements.get(i);
        OrderByElement otherOb = other.orderByElements.get(i);
        // TODO: compare equiv classes by comparing each equiv class's placeholder
        // slotref
        if (!ob.getExpr().equals(otherOb.getExpr())) return false;
        if (ob.isAsc() != otherOb.isAsc()) return false;
        if (ob.nullsFirst() != otherOb.nullsFirst()) return false;
      }
      return true;
    }

    /**
     * Adds other's window groups to ours, assuming that we're a prefix of other.
     */
    public void absorb(SortGroup other) {
      Preconditions.checkState(isPrefixOf(other));
      windowGroups.addAll(other.windowGroups);
    }

    /**
     * Compute totalOutputTupleSize.
     */
    public void init() {
      totalOutputTupleSize = 0;
      for (WindowGroup g: windowGroups) {
        TupleDescriptor outputTuple = g.physicalOutputTuple;
        Preconditions.checkState(outputTuple.isMaterialized());
        Preconditions.checkState(outputTuple.getByteSize() != -1);
        totalOutputTupleSize += outputTuple.getByteSize();
      }
    }

    private static class SizeLt implements Comparator<WindowGroup> {
      @Override
      public int compare(WindowGroup wg1, WindowGroup wg2) {
        Preconditions.checkState(wg1.physicalOutputTuple != null
            && wg1.physicalOutputTuple.getByteSize() != -1);
        Preconditions.checkState(wg2.physicalOutputTuple != null
            && wg2.physicalOutputTuple.getByteSize() != -1);
        int diff = wg1.physicalOutputTuple.getByteSize()
            - wg2.physicalOutputTuple.getByteSize();
        return (diff < 0 ? -1 : (diff > 0 ? 1 : 0));
      }
    }

    private static final SizeLt SIZE_LT;
    static {
      SIZE_LT = new SizeLt();
    }

    /**
     * Order window groups by increasing size of the output tuple. This minimizes
     * the total volume of data that needs to be buffered.
     */
    public void orderWindowGroups() {
      Collections.sort(windowGroups, SIZE_LT);
    }
  }

  /**
   * Partitions the windowGroups into SortGroups based on compatible order by exprs.
   */
  private List<SortGroup> collectSortGroups(List<WindowGroup> windowGroups) {
    List<SortGroup> sortGroups = new ArrayList<>();
    for (WindowGroup windowGroup: windowGroups) {
      boolean match = false;
      for (SortGroup sortGroup: sortGroups) {
        if (sortGroup.isCompatible(windowGroup)) {
          sortGroup.add(windowGroup);
          match = true;
          break;
        }
      }
      if (!match) sortGroups.add(new SortGroup(windowGroup));
    }
    return sortGroups;
  }

  /**
   * Collection of SortGroups that have compatible partition-by specifications.
   */
  private static class PartitionGroup {
    public List<Expr> partitionByExprs;
    public List<SortGroup> sortGroups = new ArrayList<>();

    // sum of sortGroups.windowGroups.physicalOutputTuple.getByteSize()
    public int totalOutputTupleSize = -1;

    public PartitionGroup(SortGroup sortGroup) {
      partitionByExprs = sortGroup.partitionByExprs;
      sortGroups.add(sortGroup);
      totalOutputTupleSize = sortGroup.totalOutputTupleSize;
    }

    /**
     * True if the partition exprs of sortGroup are compatible with ours.
     * For now that means equality.
     */
    public boolean isCompatible(SortGroup sortGroup) {
      return Expr.equalSets(sortGroup.partitionByExprs, partitionByExprs);
    }

    public void add(SortGroup sortGroup) {
      Preconditions.checkState(isCompatible(sortGroup));
      sortGroups.add(sortGroup);
      totalOutputTupleSize += sortGroup.totalOutputTupleSize;
    }

    /**
     * Merge 'other' into 'this'
     * - partitionByExprs is the intersection of the two
     * - sortGroups becomes the union
     */
    public void merge(PartitionGroup other) {
      partitionByExprs = Expr.intersect(partitionByExprs, other.partitionByExprs);
      Preconditions.checkState(Expr.getNumDistinctValues(partitionByExprs) >= 0);
      sortGroups.addAll(other.sortGroups);
    }

    /**
     * Order sort groups by increasing totalOutputTupleSize. This minimizes the total
     * volume of data that needs to be sorted.
     */
    public void orderSortGroups() {
      Collections.sort(sortGroups,
          new Comparator<SortGroup>() {
            @Override
            public int compare(SortGroup sg1, SortGroup sg2) {
              Preconditions.checkState(sg1.totalOutputTupleSize > 0);
              Preconditions.checkState(sg2.totalOutputTupleSize > 0);
              int diff = sg1.totalOutputTupleSize - sg2.totalOutputTupleSize;
              return (diff < 0 ? -1 : (diff > 0 ? 1 : 0));
            }
          });
      for (SortGroup sortGroup: sortGroups) {
        sortGroup.orderWindowGroups();
      }
    }
  }

  /**
   * Extract a minimal set of PartitionGroups from sortGroups.
   */
  private List<PartitionGroup> collectPartitionGroups(List<SortGroup> sortGroups) {
    List<PartitionGroup> partitionGroups = new ArrayList<>();
    for (SortGroup sortGroup: sortGroups) {
      boolean match = false;
      for (PartitionGroup partitionGroup: partitionGroups) {
        if (partitionGroup.isCompatible(sortGroup)) {
          partitionGroup.add(sortGroup);
          match = true;
          break;
        }
      }
      if (!match) partitionGroups.add(new PartitionGroup(sortGroup));
    }
    return partitionGroups;
  }
}
