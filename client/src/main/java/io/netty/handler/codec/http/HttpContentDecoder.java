/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CodecException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.ReferenceCountUtil;

import java.util.List;

/**
 * Decodes the content of the received {@link HttpRequest} and {@link HttpContent}.
 * The original content is replaced with the new content decoded by the
 * {@link EmbeddedChannel}, which is created by {@link #newContentDecoder(String)}.
 * Once decoding is finished, the value of the <tt>'Content-Encoding'</tt>
 * header is set to the target content encoding, as returned by {@link #getTargetContentEncoding(String)}.
 * Also, the <tt>'Content-Length'</tt> header is updated to the length of the
 * decoded content.  If the content encoding of the original is not supported
 * by the decoder, {@link #newContentDecoder(String)} should return {@code null}
 * so that no decoding occurs (i.e. pass-through).
 * <p>
 * Please note that this is an abstract class.  You have to extend this class
 * and implement {@link #newContentDecoder(String)} properly to make this class
 * functional.  For example, refer to the source code of {@link HttpContentDecompressor}.
 * <p>
 * This handler must be placed after {@link HttpObjectDecoder} in the pipeline
 * so that this handler can intercept HTTP requests after {@link HttpObjectDecoder}
 * converts {@link ByteBuf}s into HTTP requests.
 */
public abstract class HttpContentDecoder extends MessageToMessageDecoder<HttpObject> {

    protected ChannelHandlerContext ctx;
    private EmbeddedChannel decoder;
    private boolean continueResponse;

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
        if (msg instanceof HttpResponse && ((HttpResponse) msg).getStatus().code() == 100) {

            if (!(msg instanceof LastHttpContent)) {
                continueResponse = true;
            }
            // 100-continue response must be passed through.
            out.add(ReferenceCountUtil.retain(msg));
            return;
        }

        if (continueResponse) {
            if (msg instanceof LastHttpContent) {
                continueResponse = false;
            }
            // 100-continue response must be passed through.
            out.add(ReferenceCountUtil.retain(msg));
            return;
        }

        if (msg instanceof HttpMessage) {
            cleanup();
            final HttpMessage message = (HttpMessage) msg;
            final HttpHeaders headers = message.headers();

            // Determine the content encoding.
            String contentEncoding = headers.get(HttpHeaders.Names.CONTENT_ENCODING);
            if (contentEncoding != null) {
                contentEncoding = contentEncoding.trim();
            } else {
                contentEncoding = HttpHeaders.Values.IDENTITY;
            }
            decoder = newContentDecoder(contentEncoding);

            if (decoder == null) {
                if (message instanceof HttpContent) {
                    ((HttpContent) message).retain();
                }
                out.add(message);
                return;
            }

            // Remove content-length header:
            // the correct value can be set only after all chunks are processed/decoded.
            // If buffering is not an issue, add HttpObjectAggregator down the chain, it will set the header.
            // Otherwise, rely on LastHttpContent message.
            if (headers.contains(HttpHeaders.Names.CONTENT_LENGTH)) {
                headers.remove(HttpHeaders.Names.CONTENT_LENGTH);
                headers.set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
            }

            // set new content encoding,
            CharSequence targetContentEncoding = getTargetContentEncoding(contentEncoding);
            if (HttpHeaders.Values.IDENTITY.equals(targetContentEncoding)) {
                // Do NOT set the 'Content-Encoding' header if the target encoding is 'identity'
                // as per: http://tools.ietf.org/html/rfc2616#section-14.11
                headers.remove(HttpHeaders.Names.CONTENT_ENCODING);
            } else {
                headers.set(HttpHeaders.Names.CONTENT_ENCODING, targetContentEncoding);
            }

            if (message instanceof HttpContent) {
                // If message is a full request or response object (headers + data), don't copy data part into out.
                // Output headers only; data part will be decoded below.
                // Note: "copy" object must not be an instance of LastHttpContent class,
                // as this would (erroneously) indicate the end of the HttpMessage to other handlers.
                HttpMessage copy;
                if (message instanceof HttpRequest) {
                    HttpRequest r = (HttpRequest) message; // HttpRequest or FullHttpRequest
                    copy = new DefaultHttpRequest(r.getProtocolVersion(), r.getMethod(), r.getUri());
                } else if (message instanceof HttpResponse) {
                    HttpResponse r = (HttpResponse) message; // HttpResponse or FullHttpResponse
                    copy = new DefaultHttpResponse(r.getProtocolVersion(), r.getStatus());
                } else {
                    throw new CodecException("Object of class " + message.getClass().getName() +
                                             " is not a HttpRequest or HttpResponse");
                }
                copy.headers().set(message.headers());
                copy.setDecoderResult(message.getDecoderResult());
                out.add(copy);
            } else {
                out.add(message);
            }
        }

        if (msg instanceof HttpContent) {
            final HttpContent c = (HttpContent) msg;
            if (decoder == null) {
                out.add(c.retain());
            } else {
                decodeContent(c, out);
            }
        }
    }

    private void decodeContent(HttpContent c, List<Object> out) {
        ByteBuf content = c.content();

        decode(content, out);

        if (c instanceof LastHttpContent) {
            finishDecode(out);

            LastHttpContent last = (LastHttpContent) c;
            // Generate an additional chunk if the decoder produced
            // the last product on closure,
            HttpHeaders headers = last.trailingHeaders();
            if (headers.isEmpty()) {
                out.add(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                out.add(new ComposedLastHttpContent(headers));
            }
        }
    }

    /**
     * Returns a new {@link EmbeddedChannel} that decodes the HTTP message
     * content encoded in the specified <tt>contentEncoding</tt>.
     *
     * @param contentEncoding the value of the {@code "Content-Encoding"} header
     * @return a new {@link EmbeddedChannel} if the specified encoding is supported.
     *         {@code null} otherwise (alternatively, you can throw an exception
     *         to block unknown encoding).
     */
    protected abstract EmbeddedChannel newContentDecoder(String contentEncoding) throws Exception;

    /**
     * Returns the expected content encoding of the decoded content.
     * This getMethod returns {@code "identity"} by default, which is the case for
     * most decoders.
     *
     * @param contentEncoding the value of the {@code "Content-Encoding"} header
     * @return the expected content encoding of the new content
     */
    protected String getTargetContentEncoding(
            @SuppressWarnings("UnusedParameters") String contentEncoding) throws Exception {
        return HttpHeaders.Values.IDENTITY;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        super.handlerAdded(ctx);
    }

    private void cleanup() {
        if (decoder != null) {
            // Clean-up the previous decoder if not cleaned up correctly.
            if (decoder.finish()) {
                for (;;) {
                    ByteBuf buf = (ByteBuf) decoder.readInbound();
                    if (buf == null) {
                        break;
                    }
                    // Release the buffer
                    buf.release();
                }
            }
            decoder = null;
        }
    }

    private void decode(ByteBuf in, List<Object> out) {
        // call retain here as it will call release after its written to the channel
        decoder.writeInbound(in.retain());
        fetchDecoderOutput(out);
    }

    private void finishDecode(List<Object> out) {
        if (decoder.finish()) {
            fetchDecoderOutput(out);
        }
        decoder = null;
    }

    private void fetchDecoderOutput(List<Object> out) {
        for (;;) {
            ByteBuf buf = (ByteBuf) decoder.readInbound();
            if (buf == null) {
                break;
            }
            if (!buf.isReadable()) {
                buf.release();
                continue;
            }
            out.add(new DefaultHttpContent(buf));
        }
    }
}
