/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.filter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

import java.util.List;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_CONN_ATTR;

/**
 * 基于netty 将后端http服务响应写回客户端
 * @author Spencer Gibb
 */
public class NettyWriteResponseFilter implements GlobalFilter, Ordered {

	/**
	 * Order for write response filter.
	 */
	public static final int WRITE_RESPONSE_FILTER_ORDER = -1;

	private static final Log log = LogFactory.getLog(NettyWriteResponseFilter.class);

	private final List<MediaType> streamingMediaTypes;

	public NettyWriteResponseFilter(List<MediaType> streamingMediaTypes) {
		this.streamingMediaTypes = streamingMediaTypes;
	}

	@Override
	public int getOrder() {
		return WRITE_RESPONSE_FILTER_ORDER;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// NOTICE: nothing in "pre" filter stage as CLIENT_RESPONSE_CONN_ATTR is not added
		// until the NettyRoutingFilter is run
		// @formatter:off
		return chain.filter(exchange)
				// 报错，关闭连接
				.doOnError(throwable -> cleanup(exchange))
				//
				.then(Mono.defer(() -> {
					// 获得连接 con
					Connection connection = exchange.getAttribute(CLIENT_RESPONSE_CONN_ATTR);

					if (connection == null) {
						return Mono.empty();
					}
					if (log.isTraceEnabled()) {
						log.trace("NettyWriteResponseFilter start inbound: "
								+ connection.channel().id().asShortText() + ", outbound: "
								+ exchange.getLogPrefix());
					}
					// 返回对象
					ServerHttpResponse response = exchange.getResponse();

					// TODO: what if it's not netty
					NettyDataBufferFactory factory = (NettyDataBufferFactory) response
							.bufferFactory();

					// TODO: needed?
					// 类型转换： response.writewith()；参数类型
					final Flux<NettyDataBuffer> body = connection
							.inbound()
							.receive()
							.retain()
							.map(factory::wrap);

					MediaType contentType = null;
					try {
						// 请求体
						contentType = response.getHeaders().getContentType();
					}
					catch (Exception e) {
						if (log.isTraceEnabled()) {
							log.trace("invalid media type", e);
						}
					}
					// 返回
					return (isStreamingMediaType(contentType)
							? response.writeAndFlushWith(body.map(Flux::just))
							: response.writeWith(body));
				})).doOnCancel(() -> cleanup(exchange));
		// @formatter:on
	}

	private void cleanup(ServerWebExchange exchange) {
		Connection connection = exchange.getAttribute(CLIENT_RESPONSE_CONN_ATTR);
		if (connection != null) {
			connection.dispose();
		}
	}

	// TODO: use framework if possible
	// TODO: port to WebClientWriteResponseFilter
	private boolean isStreamingMediaType(@Nullable MediaType contentType) {
		return (contentType != null && this.streamingMediaTypes.stream()
				.anyMatch(contentType::isCompatibleWith));
	}

}
