/*
 * Copyright 2017-2019 the original author or authors.
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.filterRequest;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;
import static org.springframework.util.StringUtils.commaDelimitedListToStringArray;

/**
 * 路由网关过滤器；
 * 根据 ws:// wss://前缀（Scheme）过滤处理, 代理后端的websocket服务，提供给客户端连接
 * @author Spencer Gibb
 */
public class WebsocketRoutingFilter implements GlobalFilter, Ordered {

	/**
	 * Sec-Websocket protocol.
	 */
	public static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

	private static final Log log = LogFactory.getLog(WebsocketRoutingFilter.class);

	/** 通过该属性连接后端被代理的 websocket 服务 */
	private final WebSocketClient webSocketClient;

	/** 通过该属性处理客户端发起的连接请求 */
	private final WebSocketService webSocketService;

	private final ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider;

	// do not use this headersFilters directly, use getHeadersFilters() instead.
	private volatile List<HttpHeadersFilter> headersFilters;

	public WebsocketRoutingFilter(WebSocketClient webSocketClient,
			WebSocketService webSocketService,
			ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider) {
		this.webSocketClient = webSocketClient;
		this.webSocketService = webSocketService;
		this.headersFiltersProvider = headersFiltersProvider;
	}

	/* for testing */

	/**
	 * http/https 协议转化为 ws/wss协议
	 * http -> ws
	 * https -> wss
	 * @param scheme
	 * @return
	 */
	static String convertHttpToWs(String scheme) {
		scheme = scheme.toLowerCase();
		return "http".equals(scheme) ? "ws" : "https".equals(scheme) ? "wss" : scheme;
	}

	@Override
	public int getOrder() {
		// Before NettyRoutingFilter since this routes certain http requests
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 设置请求url; http -》 ws
		changeSchemeIfIsWebSocketUpgrade(exchange);

		// 获取 ws:// url
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		// 获得 ws
		String scheme = requestUrl.getScheme();

		// 判断是否已经路由； 不是ws /wss 协议
		if (isAlreadyRouted(exchange)
				|| (!"ws".equals(scheme) && !"wss".equals(scheme))) {
			return chain.filter(exchange);
		}
		// 设置为已经执行
		setAlreadyRouted(exchange);

		// 获得请求头
		HttpHeaders headers = exchange.getRequest().getHeaders();
		// 获取请求头过滤器链
		HttpHeaders filtered = filterRequest(getHeadersFilters(), exchange);

		// 获取 webscoket 协议
		List<String> protocols = headers.get(SEC_WEBSOCKET_PROTOCOL);
		if (protocols != null) {
			protocols = headers.get(SEC_WEBSOCKET_PROTOCOL).stream().flatMap(
					header -> Arrays.stream(commaDelimitedListToStringArray(header)))
					.map(String::trim).collect(Collectors.toList());
		}

		// 处理客户端的连接请求
		return this.webSocketService.handleRequest(exchange, new ProxyWebSocketHandler(
				requestUrl, this.webSocketClient, filtered, protocols));
	}

	/** 获得请求头过滤器 */
	private List<HttpHeadersFilter> getHeadersFilters() {
		if (this.headersFilters == null) {
			this.headersFilters = this.headersFiltersProvider
					.getIfAvailable(ArrayList::new);

			headersFilters.add((headers, exchange) -> {
				// 创建请求头
				HttpHeaders filtered = new HttpHeaders();
				headers.entrySet().stream()
						// 过滤非 sec-websocket
						.filter(entry -> !entry.getKey().toLowerCase()
								.startsWith("sec-websocket"))
						// 添加到 请求头
						.forEach(header -> filtered.addAll(header.getKey(),
								header.getValue()));
				return filtered;
			});
		}

		return this.headersFilters;
	}

	private void changeSchemeIfIsWebSocketUpgrade(ServerWebExchange exchange) {
		// Check the Upgrade 获得请求url
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		// 获得协议 http:// 、ws://、wss://
		String scheme = requestUrl.getScheme().toLowerCase();
		// upgrade机制： 客户端请求协议升级相关
		String upgrade = exchange.getRequest().getHeaders().getUpgrade();
		// change the scheme if the socket client send a "http" or "https"
		// 升级 websocket && http||https协议
		if ("WebSocket".equalsIgnoreCase(upgrade)
				&& ("http".equals(scheme) || "https".equals(scheme))) {
			// 协议转换 http/https -> ws/wss
			String wsScheme = convertHttpToWs(scheme);
			// 构建websocket 请求url: ws://192.168.1.235/user/hello
			URI wsRequestUrl = UriComponentsBuilder.fromUri(requestUrl).scheme(wsScheme)
					.build().toUri();
			// 设置请求url
			exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, wsRequestUrl);
			if (log.isTraceEnabled()) {
				log.trace("changeSchemeTo:[" + wsRequestUrl + "]");
			}
		}
	}

	/**
	 * 代理后端 websockethandler 服务处理器
	 */
	private static class ProxyWebSocketHandler implements WebSocketHandler {

		private final WebSocketClient client;

		private final URI url;

		private final HttpHeaders headers;

		private final List<String> subProtocols;

		ProxyWebSocketHandler(URI url, WebSocketClient client, HttpHeaders headers, List<String> protocols) {
			this.client = client;
			this.url = url;
			this.headers = headers;
			if (protocols != null) {
				this.subProtocols = protocols;
			}
			else {
				this.subProtocols = Collections.emptyList();
			}
		}

		@Override
		public List<String> getSubProtocols() {
			return this.subProtocols;
		}

		@Override
		public Mono<Void> handle(WebSocketSession session) {
			// pass headers along so custom headers can be sent through
			return client.execute(url, this.headers, new WebSocketHandler() {
				@Override
				public Mono<Void> handle(WebSocketSession proxySession) {
					// Use retain() for Reactor Netty
					// 转发消息 客户端 =》后端服务
					Mono<Void> proxySessionSend = proxySession
							.send(session.receive().doOnNext(WebSocketMessage::retain));
					// .log("proxySessionSend", Level.FINE);
					// 转发消息 后端服务 =》 客户端
					Mono<Void> serverSessionSend = session.send(
							proxySession.receive().doOnNext(WebSocketMessage::retain));
					// .log("sessionSend", Level.FINE);
					// 合并；
					return Mono.zip(proxySessionSend, serverSessionSend).then();
				}

				/**
				 * Copy subProtocols so they are available downstream.
				 * @return
				 */
				@Override
				public List<String> getSubProtocols() {
					return ProxyWebSocketHandler.this.subProtocols;
				}
			});
		}

	}

}
