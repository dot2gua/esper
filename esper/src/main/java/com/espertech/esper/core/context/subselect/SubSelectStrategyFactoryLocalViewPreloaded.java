/**************************************************************************************
 * Copyright (C) 2006-2015 EsperTech Inc. All rights reserved.                        *
 * http://www.espertech.com/esper                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.core.context.subselect;

import com.espertech.esper.client.EPException;
import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.EventType;
import com.espertech.esper.collection.Pair;
import com.espertech.esper.core.context.factory.StatementAgentInstancePostLoad;
import com.espertech.esper.core.context.factory.StatementAgentInstancePostLoadIndexVisitor;
import com.espertech.esper.core.context.util.AgentInstanceContext;
import com.espertech.esper.core.context.util.AgentInstanceViewFactoryChainContext;
import com.espertech.esper.core.service.EPServicesContext;
import com.espertech.esper.core.start.EPStatementStartMethodHelperPrevious;
import com.espertech.esper.core.start.EPStatementStartMethodHelperPrior;
import com.espertech.esper.epl.agg.service.AggregationService;
import com.espertech.esper.epl.agg.service.AggregationServiceFactoryDesc;
import com.espertech.esper.epl.core.StreamTypeServiceImpl;
import com.espertech.esper.epl.core.ViewResourceDelegateVerified;
import com.espertech.esper.epl.expression.core.ExprEvaluator;
import com.espertech.esper.epl.expression.core.ExprNode;
import com.espertech.esper.epl.expression.core.ExprNodeUtility;
import com.espertech.esper.epl.expression.prev.ExprPreviousEvalStrategy;
import com.espertech.esper.epl.expression.prev.ExprPreviousNode;
import com.espertech.esper.epl.expression.prior.ExprPriorEvalStrategy;
import com.espertech.esper.epl.expression.prior.ExprPriorNode;
import com.espertech.esper.epl.join.table.EventTable;
import com.espertech.esper.epl.join.table.EventTableFactory;
import com.espertech.esper.epl.join.table.EventTableFactoryTableIdentAgentInstance;
import com.espertech.esper.epl.lookup.SubordTableLookupStrategy;
import com.espertech.esper.epl.lookup.SubordTableLookupStrategyFactory;
import com.espertech.esper.epl.lookup.SubordTableLookupStrategyNullRow;
import com.espertech.esper.epl.named.NamedWindowProcessor;
import com.espertech.esper.epl.named.NamedWindowProcessorInstance;
import com.espertech.esper.epl.named.NamedWindowTailViewInstance;
import com.espertech.esper.epl.spec.NamedWindowConsumerStreamSpec;
import com.espertech.esper.epl.subquery.*;
import com.espertech.esper.filter.FilterSpecCompiled;
import com.espertech.esper.filter.FilterSpecCompiler;
import com.espertech.esper.util.StopCallback;
import com.espertech.esper.view.View;
import com.espertech.esper.view.ViewFactory;
import com.espertech.esper.view.ViewServiceCreateResult;
import com.espertech.esper.view.Viewable;
import com.espertech.esper.view.internal.BufferView;
import com.espertech.esper.view.internal.PriorEventViewFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;

/**
 * Entry holding lookup resource references for use by {@link SubSelectActivationCollection}.
 */
public class SubSelectStrategyFactoryLocalViewPreloaded implements SubSelectStrategyFactory
{
    private static Log log = LogFactory.getLog(SubSelectStrategyFactoryLocalViewPreloaded.class);
    private final static SubordTableLookupStrategyNullRow NULL_ROW_STRATEGY = new SubordTableLookupStrategyNullRow();

    private final int subqueryNumber;
    private final SubSelectActivationHolder subSelectHolder;
    private final Pair<EventTableFactory, SubordTableLookupStrategyFactory> pair;
    private final ExprNode filterExprNode;
    private final ExprEvaluator filterExprEval;
    private final boolean correlatedSubquery;
    private final AggregationServiceFactoryDesc aggregationServiceFactory;
    private final ViewResourceDelegateVerified viewResourceDelegate;
    private final ExprEvaluator[] groupKeys;

    public SubSelectStrategyFactoryLocalViewPreloaded(int subqueryNumber, SubSelectActivationHolder subSelectHolder, Pair<EventTableFactory, SubordTableLookupStrategyFactory> pair, ExprNode filterExprNode, ExprEvaluator filterExprEval, boolean correlatedSubquery, AggregationServiceFactoryDesc aggregationServiceFactory, ViewResourceDelegateVerified viewResourceDelegate, ExprEvaluator[] groupKeys) {
        this.subqueryNumber = subqueryNumber;
        this.subSelectHolder = subSelectHolder;
        this.pair = pair;
        this.filterExprNode = filterExprNode;
        this.filterExprEval = filterExprEval;
        this.correlatedSubquery = correlatedSubquery;
        this.aggregationServiceFactory = aggregationServiceFactory;
        this.viewResourceDelegate = viewResourceDelegate;
        this.groupKeys = groupKeys;
    }

    public SubSelectStrategyRealization instantiate(final EPServicesContext services,
                                                 Viewable viewableRoot,
                                                 final AgentInstanceContext agentInstanceContext,
                                                 List<StopCallback> stopCallbackList) {

        List<ViewFactory> viewFactoryChain = subSelectHolder.getViewFactoryChain().getViewFactoryChain();

        // add "prior" view factory
        boolean hasPrior = viewResourceDelegate.getPerStream()[0].getPriorRequests() != null && !viewResourceDelegate.getPerStream()[0].getPriorRequests().isEmpty();
        if (hasPrior) {
            PriorEventViewFactory priorEventViewFactory = EPStatementStartMethodHelperPrior.getPriorEventViewFactory(agentInstanceContext.getStatementContext(), 1024 + subqueryNumber, viewFactoryChain.size() + 1, viewFactoryChain.isEmpty());
            viewFactoryChain = new ArrayList<ViewFactory>(viewFactoryChain);
            viewFactoryChain.add(priorEventViewFactory);
        }

        // create factory chain context to hold callbacks specific to "prior" and "prev"
        AgentInstanceViewFactoryChainContext viewFactoryChainContext = AgentInstanceViewFactoryChainContext.create(viewFactoryChain, agentInstanceContext, viewResourceDelegate.getPerStream()[0], -1, true, subqueryNumber);

        ViewServiceCreateResult createResult = services.getViewService().createViews(viewableRoot, viewFactoryChain, viewFactoryChainContext, false);
        final Viewable subselectView = createResult.getFinalViewable();

        // create index/holder table
        final EventTable[] index = pair.getFirst().makeEventTables(new EventTableFactoryTableIdentAgentInstance(agentInstanceContext));
        stopCallbackList.add(new SubqueryStopCallback(index));

        // create strategy
        SubordTableLookupStrategy strategy = pair.getSecond().makeStrategy(index, null);
        SubselectAggregationPreprocessorBase subselectAggregationPreprocessor = null;

        // handle "prior" nodes and their strategies
        Map<ExprPriorNode, ExprPriorEvalStrategy> priorNodeStrategies = EPStatementStartMethodHelperPrior.compilePriorNodeStrategies(viewResourceDelegate, new AgentInstanceViewFactoryChainContext[]{viewFactoryChainContext});

        // handle "previous" nodes and their strategies
        Map<ExprPreviousNode, ExprPreviousEvalStrategy> previousNodeStrategies = EPStatementStartMethodHelperPrevious.compilePreviousNodeStrategies(viewResourceDelegate, new AgentInstanceViewFactoryChainContext[]{viewFactoryChainContext});

        AggregationService aggregationService = null;
        if (aggregationServiceFactory != null) {
            aggregationService = aggregationServiceFactory.getAggregationServiceFactory().makeService(agentInstanceContext, agentInstanceContext.getStatementContext().getMethodResolutionService());

            if (!correlatedSubquery) {
                View aggregatorView;
                if (groupKeys == null) {
                    if (filterExprEval == null) {
                        aggregatorView = new SubselectAggregatorViewUnfilteredUngrouped(aggregationService, filterExprEval, agentInstanceContext, null);
                    }
                    else {
                        aggregatorView = new SubselectAggregatorViewFilteredUngrouped(aggregationService, filterExprEval, agentInstanceContext, null, filterExprNode);
                    }
                }
                else {
                    if (filterExprEval == null) {
                        aggregatorView = new SubselectAggregatorViewUnfilteredGrouped(aggregationService, filterExprEval, agentInstanceContext, groupKeys);
                    }
                    else {
                        aggregatorView = new SubselectAggregatorViewFilteredGrouped(aggregationService, filterExprEval, agentInstanceContext, groupKeys, filterExprNode);
                    }
                }
                subselectView.addView(aggregatorView);

                preload(services, null, aggregatorView, agentInstanceContext);

                return new SubSelectStrategyRealization(NULL_ROW_STRATEGY, null, aggregationService, priorNodeStrategies, previousNodeStrategies, subselectView, null);
            }
            else {
                if (groupKeys == null) {
                    if (filterExprEval == null) {
                        subselectAggregationPreprocessor = new SubselectAggregationPreprocessorUnfilteredUngrouped(aggregationService, filterExprEval, null);
                    }
                    else {
                        subselectAggregationPreprocessor = new SubselectAggregationPreprocessorFilteredUngrouped(aggregationService, filterExprEval, null);
                    }
                }
                else {
                    if (filterExprEval == null) {
                        subselectAggregationPreprocessor = new SubselectAggregationPreprocessorUnfilteredGrouped(aggregationService, filterExprEval, groupKeys);
                    }
                    else {
                        subselectAggregationPreprocessor = new SubselectAggregationPreprocessorFilteredGrouped(aggregationService, filterExprEval, groupKeys);
                    }
                }
            }
        }

        // preload when allowed
        StatementAgentInstancePostLoad postLoad;
        if (services.getEventTableIndexService().allowInitIndex()) {
            preload(services, index, subselectView, agentInstanceContext);
            postLoad = new StatementAgentInstancePostLoad() {
                public void executePostLoad() {
                    preload(services, index, subselectView, agentInstanceContext);
                }

                public void acceptIndexVisitor(StatementAgentInstancePostLoadIndexVisitor visitor) {
                    for (EventTable table : index) {
                        visitor.visit(table);
                    }
                }
            };
        }
        else {
            postLoad = new StatementAgentInstancePostLoad() {
                public void executePostLoad() {
                    // no post-load
                }

                public void acceptIndexVisitor(StatementAgentInstancePostLoadIndexVisitor visitor) {
                    for (EventTable table : index) {
                        visitor.visit(table);
                    }
                }
            };
        }

        BufferView bufferView = new BufferView(subSelectHolder.getStreamNumber());
        bufferView.setObserver(new SubselectBufferObserver(index));
        subselectView.addView(bufferView);

        return new SubSelectStrategyRealization(strategy, subselectAggregationPreprocessor, aggregationService, priorNodeStrategies, previousNodeStrategies, subselectView, postLoad);
    }

    private void preload(EPServicesContext services, EventTable[] eventIndex, Viewable subselectView, AgentInstanceContext agentInstanceContext) {
        if (subSelectHolder.getStreamSpecCompiled() instanceof NamedWindowConsumerStreamSpec)
        {
            NamedWindowConsumerStreamSpec namedSpec = (NamedWindowConsumerStreamSpec) subSelectHolder.getStreamSpecCompiled();
            NamedWindowProcessor processor = services.getNamedWindowMgmtService().getProcessor(namedSpec.getWindowName());
            if (processor == null) {
                throw new RuntimeException("Failed to find named window by name '" + namedSpec.getWindowName() + "'");
            }

            NamedWindowProcessorInstance processorInstance = processor.getProcessorInstance(agentInstanceContext);
            if (processorInstance == null) {
                throw new EPException("Named window '" + namedSpec.getWindowName() + "' is associated to context '" + processor.getContextName() + "' that is not available for querying");
            }
            NamedWindowTailViewInstance consumerView = processorInstance.getTailViewInstance();

            // preload view for stream
            Collection<EventBean> eventsInWindow;
            if (namedSpec.getFilterExpressions() != null && !namedSpec.getFilterExpressions().isEmpty()) {

                try {
                    StreamTypeServiceImpl types = new StreamTypeServiceImpl(consumerView.getEventType(), consumerView.getEventType().getName(), false, services.getEngineURI());
                    LinkedHashMap<String, Pair<EventType, String>> tagged = new LinkedHashMap<String, Pair<EventType, String>>();
                    FilterSpecCompiled filterSpecCompiled = FilterSpecCompiler.makeFilterSpec(types.getEventTypes()[0], types.getStreamNames()[0],
                            namedSpec.getFilterExpressions(), null, tagged, tagged, types, null, agentInstanceContext.getStatementContext(), Collections.singleton(0));
                    Collection<EventBean> snapshot = consumerView.snapshotNoLock(filterSpecCompiled, agentInstanceContext.getStatementContext().getAnnotations());
                    eventsInWindow = new ArrayList<EventBean>(snapshot.size());
                    ExprNodeUtility.applyFilterExpressionsIterable(snapshot, namedSpec.getFilterExpressions(), agentInstanceContext, eventsInWindow);
                }
                catch (Exception ex) {
                    log.warn("Unexpected exception analyzing filter paths: " + ex.getMessage(), ex);
                    eventsInWindow = new ArrayList<EventBean>();
                    ExprNodeUtility.applyFilterExpressionsIterable(consumerView, namedSpec.getFilterExpressions(), agentInstanceContext, eventsInWindow);
                }
            }
            else {
                eventsInWindow = new ArrayList<EventBean>();
                for(Iterator<EventBean> it = consumerView.iterator(); it.hasNext();) {
                    eventsInWindow.add(it.next());
                }
            }
            EventBean[] newEvents = eventsInWindow.toArray(new EventBean[eventsInWindow.size()]);
            if (subselectView != null) {
                ((com.espertech.esper.view.View)subselectView).update(newEvents, null);
            }
            if (eventIndex != null) {
                for (EventTable table : eventIndex) {
                    table.add(newEvents);  // fill index
                }
            }
        }
        else        // preload from the data window that sit on top
        {
            // Start up event table from the iterator
            Iterator<EventBean> it = subselectView.iterator();
            if ((it != null) && (it.hasNext()))
            {
                ArrayList<EventBean> preloadEvents = new ArrayList<EventBean>();
                for (;it.hasNext();)
                {
                    preloadEvents.add(it.next());
                }
                if (eventIndex != null)
                {
                    for (EventTable table : eventIndex) {
                        table.add(preloadEvents.toArray(new EventBean[preloadEvents.size()]));
                    }
                }
            }
        }
    }
}
