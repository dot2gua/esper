/*
 ***************************************************************************************
 *  Copyright (C) 2006 EsperTech, Inc. All rights reserved.                            *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 ***************************************************************************************
 */
package com.espertech.esper.regression.multithread;

import com.espertech.esper.client.EPServiceProvider;
import com.espertech.esper.client.EPStatement;
import com.espertech.esper.supportregression.bean.SupportBean;
import com.espertech.esper.supportregression.execution.RegressionExecution;
import com.espertech.esper.supportregression.multithread.StmtJoinCallable;

import java.util.concurrent.*;

import static org.junit.Assert.assertTrue;

/**
 * Test for multithread-safety for joins.
 */
public class ExecMTStmtJoin implements RegressionExecution {
    private final static String EVENT_NAME = SupportBean.class.getName();

    public void run(EPServiceProvider epService) throws Exception {
        EPStatement stmt = epService.getEPAdministrator().createEPL("select istream * \n" +
                "  from " + EVENT_NAME + "(theString='s0')#length(1000000) as s0,\n" +
                "       " + EVENT_NAME + "(theString='s1')#length(1000000) as s1\n" +
                "where s0.longPrimitive = s1.longPrimitive\n"
        );
        trySendAndReceive(epService, 4, stmt, 1000);
        trySendAndReceive(epService, 2, stmt, 2000);
    }

    private void trySendAndReceive(EPServiceProvider epService, int numThreads, EPStatement statement, int numRepeats) throws Exception {
        ExecutorService threadPool = Executors.newFixedThreadPool(numThreads);
        Future[] future = new Future[numThreads];
        for (int i = 0; i < numThreads; i++) {
            Callable callable = new StmtJoinCallable(i, epService, statement, numRepeats);
            future[i] = threadPool.submit(callable);
        }

        threadPool.shutdown();
        threadPool.awaitTermination(10, TimeUnit.SECONDS);

        for (int i = 0; i < numThreads; i++) {
            assertTrue("Failed in " + statement.getText(), (Boolean) future[i].get());
        }
    }
}
