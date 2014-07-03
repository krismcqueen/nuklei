/*
 * Copyright 2014 Kaazing Corporation, All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kaazing.nuklei.concurrent.ringbuffer.mpsc;

import com.kaazing.nuklei.BitUtil;
import com.kaazing.nuklei.concurrent.AtomicBuffer;
import com.kaazing.nuklei.concurrent.ringbuffer.RingBufferReader;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.CyclicBarrier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Test MpscRingBuffer reader and writers in multiple threads
 */
@RunWith(Theories.class)
public class MpscRingBufferConcurrencyTest
{
    private static final int MSG_TYPE_ID = 100;
    private static final int NUM_WRITES_INDEX = 0;
    private static final int CAPACITY_INDEX = 1;
    private static final int REPS = 10 * 1000 * 1000;

    @DataPoint
    public static final int[] TWO_WRITERS_16K = { 2, 16 * 1024 };

    @DataPoint
    public static final int[] ONE_WRITER_16K = { 1, 16 * 1024 };

    @DataPoint
    public static final int[] TWO_WRITERS_64K = { 2, 64 * 1024 };

    @DataPoint
    public static final int[] ONE_WRITER_64K = { 1, 64 * 1024 };

    @Theory
    @Test(timeout = 10000)
    public void shouldExchangeMessages(final int[] params) throws Exception
    {
        final int reps = REPS;
        final int numWriters = params[NUM_WRITES_INDEX];
        final int capacity = params[CAPACITY_INDEX];
        final CyclicBarrier goBarrier = new CyclicBarrier(numWriters);

        final AtomicBuffer atomicBuffer =
                new AtomicBuffer(ByteBuffer.allocateDirect(capacity + MpscRingBuffer.STATE_TRAILER_SIZE));

        final MpscRingBufferReader reader = new MpscRingBufferReader(atomicBuffer);

        for (int i = 0; i  < numWriters; i++)
        {
            new Thread(new Writer(atomicBuffer, i, goBarrier, reps)).start();
        }

        final int[] counts = new int[numWriters];

        final RingBufferReader.ReadHandler handler = (typeId, buffer, index, length) ->
        {
            assertThat(length, is(2 * BitUtil.SIZE_OF_INT));

            final int id = buffer.getInt(index);
            final int rep = buffer.getInt(index + BitUtil.SIZE_OF_INT);

            final int count = counts[id];
            assertThat(rep, is(count));

            counts[id]++;
        };

        int msgCount = 0;
        while (msgCount < (reps * numWriters))
        {
            final int readCount = reader.read(handler, Integer.MAX_VALUE);

            if (0 == readCount)
            {
                Thread.yield();
            }

            msgCount += readCount;
        }

        assertThat(msgCount, is(reps * numWriters));
    }

    private static class Writer implements Runnable
    {
        private final int id;
        private final CyclicBarrier goBarrier;
        private final int reps;
        private final MpscRingBufferWriter writer;

        public Writer(final AtomicBuffer buffer, final int id, final CyclicBarrier goBarrier, final int reps)
        {
            this.id = id;
            this.goBarrier = goBarrier;
            this.reps = reps;
            this.writer = new MpscRingBufferWriter(buffer);
        }

        public void run()
        {
            try
            {
                goBarrier.await();
            }
            catch (final Exception ex)
            {
            }

            final int messageLength = 2 * BitUtil.SIZE_OF_INT;
            final int repsOffset = BitUtil.SIZE_OF_INT;
            final AtomicBuffer srcBuffer = new AtomicBuffer(new byte[64]);

            srcBuffer.putInt(0, id);

            for (int i = 0; i < reps; i++)
            {
                srcBuffer.putInt(repsOffset, i);

                while (!writer.write(MSG_TYPE_ID, srcBuffer, 0, messageLength))
                {
                    Thread.yield();
                }
            }
        }
    }
}