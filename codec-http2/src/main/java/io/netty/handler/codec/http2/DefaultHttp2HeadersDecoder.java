/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.internal.hpack.Decoder;
import io.netty.util.internal.UnstableApi;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_MAX_HEADER_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.MIN_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2Error.COMPRESSION_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;

@UnstableApi
public class DefaultHttp2HeadersDecoder implements Http2HeadersDecoder, Http2HeadersDecoder.Configuration {
    private static final float HEADERS_COUNT_WEIGHT_NEW = 1 / 5f;
    private static final float HEADERS_COUNT_WEIGHT_HISTORICAL = 1 - HEADERS_COUNT_WEIGHT_NEW;

    private final int maxHeaderSize;
    private final Decoder decoder;
    private final Http2HeaderTable headerTable;
    private final boolean validateHeaders;
    /**
     * Used to calculate an exponential moving average of header sizes to get an estimate of how large the data
     * structure for storing headers should be.
     */
    private float headerArraySizeAccumulator = 8;

    public DefaultHttp2HeadersDecoder() {
        this(true);
    }

    public DefaultHttp2HeadersDecoder(boolean validateHeaders) {
        this(DEFAULT_MAX_HEADER_SIZE, DEFAULT_HEADER_TABLE_SIZE, validateHeaders, 32);
    }

    public DefaultHttp2HeadersDecoder(int maxHeaderSize, int maxHeaderTableSize, boolean validateHeaders,
                                      int initialHuffmanDecodeCapacity) {
        if (maxHeaderSize <= 0) {
            throw new IllegalArgumentException("maxHeaderSize must be positive: " + maxHeaderSize);
        }
        decoder = new Decoder(maxHeaderSize, maxHeaderTableSize, initialHuffmanDecodeCapacity);
        headerTable = new Http2HeaderTableDecoder();
        this.maxHeaderSize = maxHeaderSize;
        this.validateHeaders = validateHeaders;
    }

    @Override
    public Http2HeaderTable headerTable() {
        return headerTable;
    }

    @Override
    public int maxHeaderSize() {
        return maxHeaderSize;
    }

    @Override
    public Configuration configuration() {
        return this;
    }

    @Override
    public Http2Headers decodeHeaders(ByteBuf headerBlock) throws Http2Exception {
        try {
            final Http2Headers headers = new DefaultHttp2Headers(validateHeaders, (int) headerArraySizeAccumulator);
            decoder.decode(headerBlock, headers);
            headerArraySizeAccumulator = HEADERS_COUNT_WEIGHT_NEW * headers.size() +
                                         HEADERS_COUNT_WEIGHT_HISTORICAL * headerArraySizeAccumulator;
            return headers;
        } catch (Http2Exception e) {
            throw e;
        } catch (Throwable e) {
            // Default handler for any other types of errors that may have occurred. For example,
            // the the Header builder throws IllegalArgumentException if the key or value was invalid
            // for any reason (e.g. the key was an invalid pseudo-header).
            throw connectionError(COMPRESSION_ERROR, e, e.getMessage());
        }
    }

    /**
     * {@link Http2HeaderTable} implementation to support {@link Http2HeadersDecoder}
     */
    private final class Http2HeaderTableDecoder extends DefaultHttp2HeaderTableListSize implements Http2HeaderTable {
        @Override
        public void maxHeaderTableSize(long max) throws Http2Exception {
            if (max < MIN_HEADER_TABLE_SIZE || max > MAX_HEADER_TABLE_SIZE) {
                throw connectionError(PROTOCOL_ERROR, "Header Table Size must be >= %d and <= %d but was %d",
                        MIN_HEADER_TABLE_SIZE, MAX_HEADER_TABLE_SIZE, max);
            }
            try {
                decoder.setMaxHeaderTableSize(max);
            } catch (Throwable t) {
                throw connectionError(PROTOCOL_ERROR, t.getMessage(), t);
            }
        }

        @Override
        public long maxHeaderTableSize() {
            return decoder.getMaxHeaderTableSize();
        }
    }
}
