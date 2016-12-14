/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.normalizer;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;


public class ShortCircuitingRenormalizerTests extends ESTestCase {
    private static final String JOB_ID = "foo";
    // Never reduce this below 4, otherwise some of the logic in the test will break
    private static final int TEST_SIZE = 1000;

    public void testNormalize() {
        ExecutorService threadpool = Executors.newScheduledThreadPool(10);
        try {
            ScoresUpdater scoresUpdater = mock(ScoresUpdater.class);

            boolean isPerPartitionNormalization = randomBoolean();

            ShortCircuitingRenormalizer renormalizer = new ShortCircuitingRenormalizer(JOB_ID, scoresUpdater, threadpool,
                    isPerPartitionNormalization);

            // Blast through many sets of quantiles in quick succession, faster than the normalizer can process them
            for (int i = 1; i < TEST_SIZE / 2; ++i) {
                Quantiles quantiles = new Quantiles(JOB_ID, new Date(), Integer.toString(i));
                renormalizer.renormalize(quantiles);
            }
            renormalizer.waitUntilIdle();
            for (int i = TEST_SIZE / 2; i <= TEST_SIZE; ++i) {
                Quantiles quantiles = new Quantiles(JOB_ID, new Date(), Integer.toString(i));
                renormalizer.renormalize(quantiles);
            }
            renormalizer.waitUntilIdle();

            ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
            verify(scoresUpdater, atLeastOnce()).update(stateCaptor.capture(), anyLong(), anyLong(), eq(isPerPartitionNormalization));

            List<String> quantilesUsed = stateCaptor.getAllValues();
            assertFalse(quantilesUsed.isEmpty());
            assertTrue(quantilesUsed.size() < TEST_SIZE);

            // Last quantiles state that was actually used must be the last quantiles state we supplied
            assertEquals(Integer.toString(TEST_SIZE), quantilesUsed.get(quantilesUsed.size() - 1));

            // Earlier quantiles states that were processed must have been processed in the supplied order
            int previous = 0;
            for (String state : quantilesUsed) {
                int current = Integer.parseInt(state);
                assertTrue("Out of sequence states were " + previous + " and " + current + " in " + quantilesUsed, current > previous);
                previous = current;
            }

            // The quantiles immediately before the intermediate wait for idle must have been processed
            int intermediateWaitPoint = TEST_SIZE / 2 - 1;
            assertTrue(quantilesUsed + " should contain " + intermediateWaitPoint,
                    quantilesUsed.contains(Integer.toString(intermediateWaitPoint)));
        } finally {
            threadpool.shutdown();
        }
    }
}
